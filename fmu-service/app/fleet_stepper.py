from .schemas import StepFleetRequest, StepFleetResponse
from .train_fmu_instance import TrainFMUInstance


class FleetStepper:
    def __init__(self) -> None:
        self._instances: dict[str, TrainFMUInstance] = {}

    def step(self, request: StepFleetRequest) -> StepFleetResponse:
        return StepFleetResponse(
            train_outputs=[
                self._instance_for(train.train_id).step(train)
                for train in request.trains
            ]
        )

    def _instance_for(self, train_id: str) -> TrainFMUInstance:
        if train_id not in self._instances:
            self._instances[train_id] = TrainFMUInstance(train_id)
        return self._instances[train_id]
