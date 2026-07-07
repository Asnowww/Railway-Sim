from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class VehiclePhysicsInput:
    train_id: str
    position_meters: float
    speed_meters_per_second: float
    train_mass_kg: float
    traction_command: float
    brake_command: float
    emergency_brake_command: bool
    speed_limit_meters_per_second: float
    movement_authority_distance_meters: float
    gradient: float
    curve_radius_meters: float
    rail_voltage: float
    power_available_watts: float
    door_closed: bool
    adhesion_coefficient: float
    previous_energy_consumed_kwh: float
    previous_energy_regenerated_kwh: float
    delta_seconds: float


@dataclass(frozen=True)
class VehiclePhysicsOutput:
    train_id: str
    new_position_meters: float
    new_speed_meters_per_second: float
    acceleration_meters_per_second_squared: float
    traction_force_newtons: float
    brake_force_newtons: float
    regen_brake_force_newtons: float
    traction_power_watts: float
    rail_current_amps: float
    regen_power_watts: float
    energy_consumed_kwh: float
    energy_regenerated_kwh: float
    fault_code: str


@dataclass(frozen=True)
class StepFleetRequest:
    sim_time: str
    delta_seconds: float
    trains: list[VehiclePhysicsInput]


@dataclass(frozen=True)
class StepFleetResponse:
    train_outputs: list[VehiclePhysicsOutput]


def _value(data: dict[str, Any], camel_name: str, snake_name: str) -> Any:
    if camel_name in data:
        return data[camel_name]
    return data[snake_name]


def vehicle_physics_input_from_dict(data: dict[str, Any]) -> VehiclePhysicsInput:
    return VehiclePhysicsInput(
        train_id=_value(data, "trainId", "train_id"),
        position_meters=_value(data, "positionMeters", "position_meters"),
        speed_meters_per_second=_value(data, "speedMetersPerSecond", "speed_meters_per_second"),
        train_mass_kg=_value(data, "trainMassKg", "train_mass_kg"),
        traction_command=_value(data, "tractionCommand", "traction_command"),
        brake_command=_value(data, "brakeCommand", "brake_command"),
        emergency_brake_command=_value(data, "emergencyBrakeCommand", "emergency_brake_command"),
        speed_limit_meters_per_second=_value(data, "speedLimitMetersPerSecond", "speed_limit_meters_per_second"),
        movement_authority_distance_meters=_value(data, "movementAuthorityDistanceMeters", "movement_authority_distance_meters"),
        gradient=_value(data, "gradient", "gradient"),
        curve_radius_meters=_value(data, "curveRadiusMeters", "curve_radius_meters"),
        rail_voltage=_value(data, "railVoltage", "rail_voltage"),
        power_available_watts=_value(data, "powerAvailableWatts", "power_available_watts"),
        door_closed=_value(data, "doorClosed", "door_closed"),
        adhesion_coefficient=_value(data, "adhesionCoefficient", "adhesion_coefficient"),
        previous_energy_consumed_kwh=_value(data, "previousEnergyConsumedKwh", "previous_energy_consumed_kwh"),
        previous_energy_regenerated_kwh=_value(data, "previousEnergyRegeneratedKwh", "previous_energy_regenerated_kwh"),
        delta_seconds=_value(data, "deltaSeconds", "delta_seconds"),
    )


def step_fleet_request_from_dict(data: dict[str, Any]) -> StepFleetRequest:
    return StepFleetRequest(
        sim_time=_value(data, "simTime", "sim_time"),
        delta_seconds=_value(data, "deltaSeconds", "delta_seconds"),
        trains=[vehicle_physics_input_from_dict(train) for train in data.get("trains", [])],
    )


def vehicle_physics_output_to_dict(output: VehiclePhysicsOutput) -> dict[str, Any]:
    return {
        "trainId": output.train_id,
        "newPositionMeters": output.new_position_meters,
        "newSpeedMetersPerSecond": output.new_speed_meters_per_second,
        "accelerationMetersPerSecondSquared": output.acceleration_meters_per_second_squared,
        "tractionForceNewtons": output.traction_force_newtons,
        "brakeForceNewtons": output.brake_force_newtons,
        "regenBrakeForceNewtons": output.regen_brake_force_newtons,
        "tractionPowerWatts": output.traction_power_watts,
        "railCurrentAmps": output.rail_current_amps,
        "regenPowerWatts": output.regen_power_watts,
        "energyConsumedKwh": output.energy_consumed_kwh,
        "energyRegeneratedKwh": output.energy_regenerated_kwh,
        "faultCode": output.fault_code,
    }


def step_fleet_response_to_dict(response: StepFleetResponse) -> dict[str, Any]:
    return {
        "trainOutputs": [
            vehicle_physics_output_to_dict(output) for output in response.train_outputs
        ]
    }
