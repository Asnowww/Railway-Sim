from .schemas import StepFleetRequest, StepFleetResponse
from .train_fmu_instance import TrainFMUInstance
from .vehicle_parameters import VehicleParameters


class FleetStepper:
    def __init__(self, parameters: VehicleParameters) -> None:
        self._parameters = parameters
        self._instances: dict[str, TrainFMUInstance] = {}

    def step(self, request: StepFleetRequest) -> StepFleetResponse:
        return StepFleetResponse(
            tick=request.tick,
            model_version=request.model_version,
            parameter_set_id=request.parameter_set_id,
            trace_id=request.trace_id,
            train_outputs=[
                self._instance_for(train.train_id).step(train)
                for train in request.trains
            ],
            train_errors=[],
        )

    def _instance_for(self, train_id: str) -> TrainFMUInstance:
        if train_id not in self._instances:
            self._instances[train_id] = TrainFMUInstance(train_id, self._parameters)
        return self._instances[train_id]
