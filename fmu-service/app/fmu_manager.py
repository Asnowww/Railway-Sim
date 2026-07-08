from .fleet_stepper import FleetStepper
from .schemas import StepFleetRequest, StepFleetResponse


class FmuManager:
    def __init__(self) -> None:
        self._fleet_stepper = FleetStepper()

    def step_fleet(self, request: StepFleetRequest) -> StepFleetResponse:
        return self._fleet_stepper.step(request)
