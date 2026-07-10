from .schemas import VehiclePhysicsInput, VehiclePhysicsOutput
from .simple_fallback import SimpleFallbackModel
from .vehicle_parameters import VehicleParameters


class TrainFMUInstance:
    def __init__(self, train_id: str, parameters: VehicleParameters) -> None:
        self.train_id = train_id
        self._fallback = SimpleFallbackModel(parameters)

    def step(self, train: VehiclePhysicsInput) -> VehiclePhysicsOutput:
        # Replace with an FMPy/PyFMI-backed FMU instance once TrainTractionBrake.fmu is exported.
        return self._fallback.step(train)
