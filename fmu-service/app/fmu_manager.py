from __future__ import annotations

from collections import OrderedDict
from dataclasses import asdict, dataclass
import hashlib
import json
import math
from threading import RLock
from typing import Any

from .fmu_runtime import FmuModelRuntime
from .schemas import (
    MODEL_VERSION,
    StepFleetRequest,
    StepFleetResponse,
    TrainStepError,
    VehiclePhysicsInput,
)
from .train_fmu_instance import FmuExecutionError, TrainFMUInstance
from .vehicle_parameters import VehicleParameters, load_vehicle_parameters


class FmuProtocolError(ValueError):
    def __init__(self, status_code: int, error_code: str, message: str) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.error_code = error_code
        self.trace_id = ""


class ParameterSetMismatchError(FmuProtocolError):
    def __init__(self, message: str) -> None:
        super().__init__(409, "FMU_PARAMETER_SET_MISMATCH", message)


@dataclass(frozen=True)
class CachedFleetResponse:
    request_hash: str
    response: StepFleetResponse


class FmuManager:
    CACHE_LIMIT = 512
    STEP_SIZE_SECONDS = 0.1
    TIME_TOLERANCE_SECONDS = 1e-9

    def __init__(
        self,
        parameters: VehicleParameters | None = None,
        runtime: FmuModelRuntime | None = None,
    ) -> None:
        self._parameters = parameters or load_vehicle_parameters()
        self._runtime = runtime or FmuModelRuntime(self._parameters)
        self._instances: dict[str, TrainFMUInstance] = {}
        self._response_cache: OrderedDict[int, CachedFleetResponse] = OrderedDict()
        self._lock = RLock()
        self._closed = False
        self._duplicate_tick_count = 0
        self._out_of_order_reject_count = 0
        self._tick_conflict_count = 0
        self._fmi_error_count = 0
        self._last_successful_tick: int | None = None

    @property
    def parameter_set_id(self) -> str:
        return self._parameters.parameter_set_id

    @property
    def model_version(self) -> str:
        return self._runtime.model_version

    def metadata(self) -> dict[str, Any]:
        metadata = self._runtime.metadata()
        metadata.update(
            {
                "parameterSource": str(self._parameters.source_path),
                "trainType": self._parameters.train_type,
                "maxMechanicalTractionPowerWatts": self._parameters.traction.max_power_watts,
            }
        )
        return metadata

    def health(self) -> dict[str, Any]:
        with self._lock:
            failed_count = sum(
                instance.state == "FAILED" for instance in self._instances.values()
            )
            return {
                "status": "UP" if not self._closed else "DOWN",
                "ready": not self._closed,
                "modelVersion": self.model_version,
                "parameterSetId": self.parameter_set_id,
                "instanceCount": len(self._instances),
                "activeInstanceCount": sum(
                    instance.state == "ACTIVE" for instance in self._instances.values()
                ),
                "failedInstanceCount": failed_count,
                "lastSuccessfulTick": self._last_successful_tick,
                "duplicateTickCount": self._duplicate_tick_count,
                "tickConflictCount": self._tick_conflict_count,
                "outOfOrderRejectCount": self._out_of_order_reject_count,
                "fmiErrorCount": self._fmi_error_count,
                "variableValidationStatus": self._runtime.validation_report["status"],
            }

    def validate_fmu(self) -> dict[str, Any]:
        with self._lock:
            self._require_open()
            return self._runtime.validate()

    def step_fleet(self, request: StepFleetRequest) -> StepFleetResponse:
        request_hash = self._request_hash(request)
        with self._lock:
            self._require_open()
            self._validate_request(request)

            cached = self._response_cache.get(request.tick)
            if cached is not None:
                if cached.request_hash == request_hash:
                    self._duplicate_tick_count += 1
                    return cached.response
                self._tick_conflict_count += 1
                raise FmuProtocolError(
                    409,
                    "FMU_TICK_CONFLICT",
                    f"tick {request.tick} was already processed with a different request",
                )

            self._preflight_instances(request)

            outputs = []
            errors = []
            for train in request.trains:
                instance = self._instances.get(train.train_id)
                if instance is None:
                    instance = TrainFMUInstance(train.train_id, self._runtime)
                    self._instances[train.train_id] = instance
                try:
                    if train.lifecycle_command == "STEP":
                        output = instance.step(
                            train,
                            tick=request.tick,
                            step_size_seconds=request.step_size_seconds,
                        )
                    else:
                        output = instance.initialize_and_step(
                            train,
                            tick=request.tick,
                            simulation_time_seconds=request.simulation_time_seconds,
                            step_size_seconds=request.step_size_seconds,
                        )
                    outputs.append(output)
                except Exception as exc:
                    self._fmi_error_count += 1
                    message = f"{type(exc).__name__}: {exc}"
                    instance.mark_failed(message)
                    errors.append(
                        TrainStepError(
                            train_id=train.train_id,
                            fault_code="FMU_STEP_FAILED",
                            message=message,
                            fmi_status=self._fmi_status(exc),
                        )
                    )

            response = StepFleetResponse(
                tick=request.tick,
                model_version=self.model_version,
                parameter_set_id=self.parameter_set_id,
                trace_id=request.trace_id,
                train_outputs=outputs,
                train_errors=errors,
            )
            self._cache_response(request.tick, request_hash, response)
            if outputs:
                self._last_successful_tick = request.tick
            return response

    def _validate_request(self, request: StepFleetRequest) -> None:
        if isinstance(request.tick, bool) or request.tick < 0:
            raise FmuProtocolError(400, "INVALID_REQUEST", "tick must be >= 0")
        self._finite("simulationTimeSeconds", request.simulation_time_seconds)
        if request.simulation_time_seconds < 0:
            raise FmuProtocolError(
                400, "INVALID_REQUEST", "simulationTimeSeconds must be >= 0"
            )
        self._finite("stepSizeSeconds", request.step_size_seconds)
        if not math.isclose(
            request.step_size_seconds,
            self.STEP_SIZE_SECONDS,
            rel_tol=0.0,
            abs_tol=1e-12,
        ):
            raise FmuProtocolError(
                400,
                "INVALID_STEP_SIZE",
                f"stepSizeSeconds must be exactly {self.STEP_SIZE_SECONDS}",
            )
        if request.model_version != self.model_version:
            raise FmuProtocolError(
                409,
                "FMU_MODEL_VERSION_MISMATCH",
                f"request modelVersion {request.model_version!r} does not match {self.model_version!r}",
            )
        if request.parameter_set_id != self.parameter_set_id:
            raise ParameterSetMismatchError(
                "request parameterSetId does not match the loaded vehicle parameter set"
            )
        if not request.trace_id.strip():
            raise FmuProtocolError(400, "INVALID_REQUEST", "traceId must not be blank")

        train_ids = [train.train_id for train in request.trains]
        duplicates = sorted(
            train_id for train_id in set(train_ids) if train_ids.count(train_id) > 1
        )
        if duplicates:
            raise FmuProtocolError(
                400,
                "DUPLICATE_TRAIN_ID",
                f"trainId values must be unique within a batch: {duplicates}",
            )
        for train in request.trains:
            self._validate_train(train)

    def _validate_train(self, train: VehiclePhysicsInput) -> None:
        if not train.train_id.strip():
            raise FmuProtocolError(400, "INVALID_REQUEST", "trainId must not be blank")
        if train.lifecycle_command not in {"INIT", "STEP", "RESET", "RESYNC"}:
            raise FmuProtocolError(
                400,
                "INVALID_LIFECYCLE_COMMAND",
                f"unsupported lifecycleCommand {train.lifecycle_command!r}",
            )
        if not train.dynamics_state.strip() or not train.dynamics_constraint_reason.strip():
            raise FmuProtocolError(
                400,
                "INVALID_REQUEST",
                "dynamicsState and dynamicsConstraintReason must not be blank",
            )

        numeric_values = {
            "positionMeters": train.position_meters,
            "speedMetersPerSecond": train.speed_meters_per_second,
            "trainMassKg": train.train_mass_kg,
            "tractionCommand": train.traction_command,
            "brakeCommand": train.brake_command,
            "gradient": train.gradient,
            "curveRadiusMeters": train.curve_radius_meters,
            "railVoltage": train.rail_voltage,
            "powerAvailableWatts": train.power_available_watts,
            "regenPowerAvailableWatts": train.regen_power_available_watts,
            "adhesionCoefficient": train.adhesion_coefficient,
            "previousEnergyConsumedKwh": train.previous_energy_consumed_kwh,
            "previousEnergyRegeneratedKwh": train.previous_energy_regenerated_kwh,
            "speedLimitMetersPerSecond": train.speed_limit_meters_per_second,
            "movementAuthorityDistanceMeters": train.movement_authority_distance_meters,
            "stationDistanceMeters": train.station_distance_meters,
            "stoppingDistanceMeters": train.stopping_distance_meters,
            "deltaSeconds": train.delta_seconds,
        }
        for name, value in numeric_values.items():
            self._finite(name, value)

        if train.speed_meters_per_second < 0:
            self._invalid_range("speedMetersPerSecond", ">= 0")
        if train.train_mass_kg <= 0:
            self._invalid_range("trainMassKg", "> 0")
        if not 0 <= train.traction_command <= 1:
            self._invalid_range("tractionCommand", "in [0, 1]")
        if not 0 <= train.brake_command <= 1:
            self._invalid_range("brakeCommand", "in [0, 1]")
        if train.curve_radius_meters <= 0:
            self._invalid_range("curveRadiusMeters", "> 0")
        if not 0.2 <= train.adhesion_coefficient <= 1:
            self._invalid_range("adhesionCoefficient", "in [0.2, 1]")
        non_negative = {
            "railVoltage": train.rail_voltage,
            "powerAvailableWatts": train.power_available_watts,
            "regenPowerAvailableWatts": train.regen_power_available_watts,
            "previousEnergyConsumedKwh": train.previous_energy_consumed_kwh,
            "previousEnergyRegeneratedKwh": train.previous_energy_regenerated_kwh,
            "speedLimitMetersPerSecond": train.speed_limit_meters_per_second,
            "movementAuthorityDistanceMeters": train.movement_authority_distance_meters,
            "stationDistanceMeters": train.station_distance_meters,
            "stoppingDistanceMeters": train.stopping_distance_meters,
        }
        for name, value in non_negative.items():
            if value < 0:
                self._invalid_range(name, ">= 0")
        if not math.isclose(
            train.delta_seconds,
            self.STEP_SIZE_SECONDS,
            rel_tol=0.0,
            abs_tol=1e-12,
        ):
            self._invalid_range("deltaSeconds", f"= {self.STEP_SIZE_SECONDS}")

    def _preflight_instances(self, request: StepFleetRequest) -> None:
        for train in request.trains:
            instance = self._instances.get(train.train_id)
            command = train.lifecycle_command
            if command == "INIT":
                if instance is not None:
                    raise FmuProtocolError(
                        409,
                        "FMU_INSTANCE_ALREADY_EXISTS",
                        f"FMU instance already exists for train {train.train_id}",
                    )
                continue
            if instance is None and command == "RESYNC":
                # 9300 uses RESYNC after a 9000 process restart. The authoritative
                # position, speed and energy in that request are sufficient to rebuild.
                continue
            if instance is None:
                raise FmuProtocolError(
                    409,
                    "FMU_INSTANCE_NOT_FOUND",
                    f"FMU instance does not exist for train {train.train_id}",
                )
            if instance.state == "FAILED" and command not in {"RESET", "RESYNC"}:
                raise FmuProtocolError(
                    409,
                    "FMU_INSTANCE_FAILED",
                    f"FMU instance {train.train_id} is failed and requires RESET or RESYNC",
                )
            if command == "STEP":
                last_tick = instance.last_successful_tick
                if last_tick is not None and request.tick < last_tick:
                    self._out_of_order_reject_count += 1
                    raise FmuProtocolError(
                        409,
                        "FMU_TICK_OUT_OF_ORDER",
                        f"tick {request.tick} is older than train {train.train_id} tick {last_tick}",
                    )
                if last_tick is not None and request.tick == last_tick:
                    self._tick_conflict_count += 1
                    raise FmuProtocolError(
                        409,
                        "FMU_TICK_CONFLICT",
                        f"tick {request.tick} was already applied to train {train.train_id}",
                    )
                current_time = instance.current_time_seconds
                if current_time is None or not math.isclose(
                    request.simulation_time_seconds,
                    current_time,
                    rel_tol=0.0,
                    abs_tol=self.TIME_TOLERANCE_SECONDS,
                ):
                    raise FmuProtocolError(
                        409,
                        "FMU_SIMULATION_TIME_CONFLICT",
                        f"train {train.train_id} expects simulationTimeSeconds={current_time}, "
                        f"got {request.simulation_time_seconds}",
                    )

    @staticmethod
    def _finite(name: str, value: float) -> None:
        if isinstance(value, bool) or not math.isfinite(float(value)):
            raise FmuProtocolError(400, "INVALID_REQUEST", f"{name} must be finite")

    @staticmethod
    def _invalid_range(name: str, expected: str) -> None:
        raise FmuProtocolError(
            400, "INVALID_REQUEST", f"{name} must be {expected}"
        )

    @staticmethod
    def _request_hash(request: StepFleetRequest) -> str:
        canonical = json.dumps(
            asdict(request),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
        return "sha256:" + hashlib.sha256(canonical).hexdigest()

    def _cache_response(
        self,
        tick: int,
        request_hash: str,
        response: StepFleetResponse,
    ) -> None:
        self._response_cache[tick] = CachedFleetResponse(request_hash, response)
        self._response_cache.move_to_end(tick)
        while len(self._response_cache) > self.CACHE_LIMIT:
            self._response_cache.popitem(last=False)

    @staticmethod
    def _fmi_status(exc: Exception) -> str:
        if isinstance(exc, FmuExecutionError):
            return exc.fmi_status
        status = getattr(exc, "status", None)
        return {
            0: "OK",
            1: "WARNING",
            2: "DISCARD",
            3: "ERROR",
            4: "FATAL",
            5: "PENDING",
        }.get(status, "ERROR")

    def delete_instance(self, train_id: str) -> dict[str, Any]:
        with self._lock:
            self._require_open()
            instance = self._instances.pop(train_id, None)
            if instance is None:
                raise FmuProtocolError(
                    404,
                    "FMU_INSTANCE_NOT_FOUND",
                    f"FMU instance does not exist for train {train_id}",
                )
            previous_state = instance.state
            instance.close()
            self._response_cache.clear()
            return {
                "trainId": train_id,
                "previousState": previous_state,
                "instanceState": "TERMINATED",
            }

    def reset_all(self) -> dict[str, Any]:
        with self._lock:
            self._require_open()
            count = len(self._instances)
            for instance in self._instances.values():
                instance.close()
            self._instances.clear()
            self._response_cache.clear()
            return {"resetInstanceCount": count, "instanceCount": 0}

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            for instance in self._instances.values():
                instance.close()
            self._instances.clear()
            self._response_cache.clear()
            self._runtime.close()
            self._closed = True

    def _require_open(self) -> None:
        if self._closed:
            raise FmuProtocolError(503, "FMU_SERVICE_NOT_READY", "FMU service is closed")


if MODEL_VERSION != "TrainTractionBrake/1.0.0":
    raise RuntimeError("Python domain model version changed without updating the frozen contract")
