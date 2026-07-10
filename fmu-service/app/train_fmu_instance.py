from __future__ import annotations

import math
from threading import RLock
from typing import Mapping

from .fmu_runtime import FmuModelRuntime, VariableBinding
from .schemas import VehiclePhysicsInput, VehiclePhysicsOutput


FAULT_CODES = {
    0: "OK",
    10: "DOOR_NOT_LOCKED",
    20: "ATP_BRAKE",
    30: "CURRENT_COLLECTION_LOST",
    31: "LOW_VOLTAGE",
    90: "FMU_INTERNAL_ERROR",
}


class FmuExecutionError(RuntimeError):
    def __init__(self, message: str, fmi_status: str = "ERROR") -> None:
        super().__init__(message)
        self.fmi_status = fmi_status


class TrainFMUInstance:
    """One persistent FMI 2.0 Co-Simulation slave owned by one train ID."""

    def __init__(self, train_id: str, runtime: FmuModelRuntime) -> None:
        self.train_id = train_id
        self._runtime = runtime
        self._lock = RLock()
        self._slave = None
        self._instantiated = False
        self.state = "NEW"
        self.current_time_seconds: float | None = None
        self.last_successful_tick: int | None = None
        self.last_output: VehiclePhysicsOutput | None = None
        self.last_error: str | None = None

    def initialize_and_step(
        self,
        train: VehiclePhysicsInput,
        tick: int,
        simulation_time_seconds: float,
        step_size_seconds: float,
    ) -> VehiclePhysicsOutput:
        with self._lock:
            self._release_native()
            self.state = "NEW"
            self.last_error = None
            self.current_time_seconds = simulation_time_seconds
            self.last_successful_tick = None
            self.last_output = None

            slave = self._runtime.create_slave(self.train_id)
            self._slave = slave
            slave.instantiate(visible=False, loggingOn=False)
            self._instantiated = True
            slave.setupExperiment(startTime=simulation_time_seconds)
            slave.enterInitializationMode()
            self._set_values(
                self._runtime.group_bindings("parameters"),
                self._runtime.parameter_values(),
            )
            self._set_values(
                self._runtime.group_bindings("initialState"),
                self._initial_state_values(train),
            )
            self._set_values(
                self._runtime.group_bindings("stepInputs"),
                self._step_input_values(train),
            )
            slave.exitInitializationMode()
            self.state = "ACTIVE"
            return self._do_step(tick, step_size_seconds)

    def step(
        self,
        train: VehiclePhysicsInput,
        tick: int,
        step_size_seconds: float,
    ) -> VehiclePhysicsOutput:
        with self._lock:
            if self.state != "ACTIVE" or self._slave is None:
                raise FmuExecutionError(
                    f"FMU instance {self.train_id} is not active: {self.state}"
                )
            self._set_values(
                self._runtime.group_bindings("stepInputs"),
                self._step_input_values(train),
            )
            return self._do_step(tick, step_size_seconds)

    def _do_step(self, tick: int, step_size_seconds: float) -> VehiclePhysicsOutput:
        if self._slave is None or self.current_time_seconds is None:
            raise FmuExecutionError(f"FMU instance {self.train_id} is not initialized")
        self._slave.doStep(
            currentCommunicationPoint=self.current_time_seconds,
            communicationStepSize=step_size_seconds,
            noSetFMUStatePriorToCurrentPoint=True,
        )
        self.current_time_seconds += step_size_seconds
        output = self._read_output()
        self.last_successful_tick = tick
        self.last_output = output
        return output

    def _set_values(
        self,
        bindings: Mapping[str, VariableBinding],
        values: Mapping[str, float | bool],
    ) -> None:
        if self._slave is None:
            raise FmuExecutionError(f"FMU instance {self.train_id} is not allocated")
        real_references: list[int] = []
        real_values: list[float] = []
        boolean_references: list[int] = []
        boolean_values: list[bool] = []
        for logical_name, binding in bindings.items():
            if logical_name not in values:
                raise FmuExecutionError(f"Missing FMU value for {logical_name}")
            value = values[logical_name]
            if binding.value_type == "Real":
                numeric_value = float(value)
                if not math.isfinite(numeric_value):
                    raise FmuExecutionError(f"Non-finite FMU input {logical_name}")
                real_references.append(binding.value_reference)
                real_values.append(numeric_value)
            elif binding.value_type == "Boolean":
                boolean_references.append(binding.value_reference)
                boolean_values.append(bool(value))
            else:
                raise FmuExecutionError(
                    f"Unsupported FMU input type {binding.value_type} for {logical_name}"
                )
        if real_references:
            self._slave.setReal(real_references, real_values)
        if boolean_references:
            self._slave.setBoolean(boolean_references, boolean_values)

    @staticmethod
    def _initial_state_values(train: VehiclePhysicsInput) -> Mapping[str, float]:
        return {
            "positionMeters": train.position_meters,
            "speedMetersPerSecond": train.speed_meters_per_second,
            "previousEnergyConsumedKwh": train.previous_energy_consumed_kwh,
            "previousEnergyRegeneratedKwh": train.previous_energy_regenerated_kwh,
        }

    @staticmethod
    def _step_input_values(train: VehiclePhysicsInput) -> Mapping[str, float | bool]:
        return {
            "trainMassKg": train.train_mass_kg,
            "tractionCommand": train.traction_command,
            "brakeCommand": train.brake_command,
            "emergencyBrakeCommand": train.emergency_brake_command,
            "doorClosed": train.door_closed,
            "gradient": train.gradient,
            "railVoltage": train.rail_voltage,
            "powerAvailableWatts": train.power_available_watts,
            "regenPowerAvailableWatts": train.regen_power_available_watts,
            "currentCollectionAvailable": train.current_collection_available,
            "adhesionCoefficient": train.adhesion_coefficient,
        }

    def _read_output(self) -> VehiclePhysicsOutput:
        if self._slave is None:
            raise FmuExecutionError(f"FMU instance {self.train_id} is not allocated")
        output_bindings = self._runtime.group_bindings("outputs")
        real_items = [
            (logical_name, binding)
            for logical_name, binding in output_bindings.items()
            if binding.value_type == "Real"
        ]
        real_values = self._slave.getReal(
            [binding.value_reference for _, binding in real_items]
        )
        values = {
            logical_name: self._finite_output(logical_name, float(value))
            for (logical_name, _), value in zip(real_items, real_values, strict=True)
        }
        fault_binding = output_bindings["faultCode"]
        fault_value = int(self._slave.getInteger([fault_binding.value_reference])[0])
        fault_code = FAULT_CODES.get(fault_value, "FMU_INTERNAL_ERROR")

        return VehiclePhysicsOutput(
            train_id=self.train_id,
            new_position_meters=values["newPositionMeters"],
            new_speed_meters_per_second=self._non_negative(
                "newSpeedMetersPerSecond", values["newSpeedMetersPerSecond"]
            ),
            acceleration_meters_per_second_squared=values[
                "accelerationMetersPerSecondSquared"
            ],
            traction_force_newtons=self._non_negative(
                "tractionForceNewtons", values["tractionForceNewtons"]
            ),
            brake_force_newtons=self._non_negative(
                "brakeForceNewtons", values["brakeForceNewtons"]
            ),
            regen_brake_force_newtons=self._non_negative(
                "regenBrakeForceNewtons", values["regenBrakeForceNewtons"]
            ),
            mechanical_traction_power_watts=self._non_negative(
                "mechanicalTractionPowerWatts",
                values["mechanicalTractionPowerWatts"],
            ),
            traction_power_watts=self._non_negative(
                "tractionPowerWatts", values["tractionPowerWatts"]
            ),
            rail_current_amps=self._non_negative(
                "railCurrentAmps", values["railCurrentAmps"]
            ),
            mechanical_regen_power_watts=self._non_negative(
                "mechanicalRegenPowerWatts", values["mechanicalRegenPowerWatts"]
            ),
            regen_power_watts=self._non_negative(
                "regenPowerWatts", values["regenPowerWatts"]
            ),
            energy_consumed_kwh=self._non_negative(
                "energyConsumedKwh", values["energyConsumedKwh"]
            ),
            energy_regenerated_kwh=self._non_negative(
                "energyRegeneratedKwh", values["energyRegeneratedKwh"]
            ),
            fault_code=fault_code,
            instance_state="ACTIVE",
            data_quality="GOOD" if fault_code == "OK" else "DEGRADED",
            fmi_status="OK",
        )

    @staticmethod
    def _finite_output(name: str, value: float) -> float:
        if not math.isfinite(value):
            raise FmuExecutionError(f"FMU output {name} is not finite")
        return value

    @staticmethod
    def _non_negative(name: str, value: float) -> float:
        if value < -1e-7:
            raise FmuExecutionError(f"FMU output {name} is negative: {value}")
        return max(0.0, value)

    def mark_failed(self, message: str) -> None:
        with self._lock:
            self.last_error = message
            self._release_native()
            self.state = "FAILED"

    def close(self) -> None:
        with self._lock:
            self._release_native()
            self.state = "TERMINATED"

    def _release_native(self) -> None:
        slave = self._slave
        self._slave = None
        if slave is None:
            self._instantiated = False
            return
        if self._instantiated:
            try:
                slave.terminate()
            except Exception:
                pass
            try:
                slave.freeInstance()
            except Exception:
                pass
        self._instantiated = False
