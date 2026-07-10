from __future__ import annotations

from dataclasses import replace

from .fleet_stepper import FleetStepper
from .schemas import MODEL_VERSION, StepFleetRequest, StepFleetResponse
from .vehicle_parameters import VehicleParameters, load_vehicle_parameters


class ParameterSetMismatchError(ValueError):
    pass


class FmuManager:
    def __init__(self, parameters: VehicleParameters | None = None) -> None:
        self._parameters = parameters or load_vehicle_parameters()
        self._fleet_stepper = FleetStepper(self._parameters)

    @property
    def parameter_set_id(self) -> str:
        return self._parameters.parameter_set_id

    def metadata(self) -> dict[str, object]:
        return {
            "modelVersion": MODEL_VERSION,
            "parameterSetId": self._parameters.parameter_set_id,
            "parameterSource": str(self._parameters.source_path),
            "trainType": self._parameters.train_type,
            "maxMechanicalTractionPowerWatts": self._parameters.traction.max_power_watts,
        }

    def step_fleet(self, request: StepFleetRequest) -> StepFleetResponse:
        if request.parameter_set_id and request.parameter_set_id != self._parameters.parameter_set_id:
            raise ParameterSetMismatchError(
                "request parameterSetId does not match the loaded vehicle parameter set"
            )
        effective_request = replace(
            request,
            parameter_set_id=self._parameters.parameter_set_id,
        )
        return self._fleet_stepper.step(effective_request)
