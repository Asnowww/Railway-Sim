from dataclasses import dataclass
from typing import Any


MODEL_VERSION = "TrainTractionBrake/2.0.0"


@dataclass(frozen=True)
class VehiclePhysicsInput:
    train_id: str
    lifecycle_command: str
    section_id: str
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
    regen_power_available_watts: float
    current_collection_available: bool
    door_closed: bool
    adhesion_coefficient: float
    previous_energy_consumed_kwh: float
    previous_energy_regenerated_kwh: float
    delta_seconds: float
    dynamics_state: str = "COASTING"
    dynamics_constraint_reason: str = "NONE"
    station_distance_meters: float = 0.0
    stopping_distance_meters: float = 0.0


@dataclass(frozen=True)
class VehiclePhysicsOutput:
    train_id: str
    new_position_meters: float
    new_speed_meters_per_second: float
    acceleration_meters_per_second_squared: float
    traction_force_newtons: float
    brake_force_newtons: float
    regen_brake_force_newtons: float
    motor_speed_rpm: float
    interpolated_traction_torque_nm_per_motor: float
    interpolated_brake_torque_nm_per_motor: float
    air_brake_force_newtons: float
    mechanical_traction_power_watts: float
    traction_power_watts: float
    rail_current_amps: float
    mechanical_regen_power_watts: float
    regen_power_watts: float
    energy_consumed_kwh: float
    energy_regenerated_kwh: float
    fault_code: str
    instance_state: str = "ACTIVE"
    data_quality: str = "GOOD"
    fmi_status: str = "OK"


@dataclass(frozen=True)
class TrainStepError:
    train_id: str
    fault_code: str
    message: str
    instance_state: str = "FAILED"
    data_quality: str = "ERROR"
    fmi_status: str = "ERROR"


@dataclass(frozen=True)
class StepFleetRequest:
    tick: int
    simulation_time_seconds: float
    step_size_seconds: float
    model_version: str
    parameter_set_id: str
    trace_id: str
    trains: list[VehiclePhysicsInput]


@dataclass(frozen=True)
class StepFleetResponse:
    tick: int
    model_version: str
    parameter_set_id: str
    trace_id: str
    train_outputs: list[VehiclePhysicsOutput]
    train_errors: list[TrainStepError]


def _value(data: dict[str, Any], camel_name: str, snake_name: str) -> Any:
    if camel_name in data:
        return data[camel_name]
    return data[snake_name]


def _optional_value(data: dict[str, Any], camel_name: str, snake_name: str, default: Any) -> Any:
    if camel_name in data:
        return data[camel_name]
    return data.get(snake_name, default)


def vehicle_physics_input_from_dict(
    data: dict[str, Any],
    step_size_seconds: float,
) -> VehiclePhysicsInput:
    return VehiclePhysicsInput(
        train_id=_value(data, "trainId", "train_id"),
        lifecycle_command=_optional_value(data, "lifecycleCommand", "lifecycle_command", "STEP"),
        section_id=_optional_value(data, "sectionId", "section_id", ""),
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
        regen_power_available_watts=_optional_value(
            data,
            "regenPowerAvailableWatts",
            "regen_power_available_watts",
            0.0,
        ),
        current_collection_available=_optional_value(
            data,
            "currentCollectionAvailable",
            "current_collection_available",
            True,
        ),
        door_closed=_value(data, "doorClosed", "door_closed"),
        adhesion_coefficient=_value(data, "adhesionCoefficient", "adhesion_coefficient"),
        previous_energy_consumed_kwh=_value(data, "previousEnergyConsumedKwh", "previous_energy_consumed_kwh"),
        previous_energy_regenerated_kwh=_value(data, "previousEnergyRegeneratedKwh", "previous_energy_regenerated_kwh"),
        delta_seconds=_optional_value(data, "deltaSeconds", "delta_seconds", step_size_seconds),
        dynamics_state=_optional_value(data, "dynamicsState", "dynamics_state", "COASTING"),
        dynamics_constraint_reason=_optional_value(
            data,
            "dynamicsConstraintReason",
            "dynamics_constraint_reason",
            "NONE",
        ),
        station_distance_meters=_optional_value(data, "stationDistanceMeters", "station_distance_meters", 0.0),
        stopping_distance_meters=_optional_value(data, "stoppingDistanceMeters", "stopping_distance_meters", 0.0),
    )


def step_fleet_request_from_dict(data: dict[str, Any]) -> StepFleetRequest:
    step_size_seconds = float(
        _optional_value(
            data,
            "stepSizeSeconds",
            "step_size_seconds",
            _optional_value(data, "deltaSeconds", "delta_seconds", 0.02),
        )
    )
    tick = int(_optional_value(data, "tick", "tick", 0))
    return StepFleetRequest(
        tick=tick,
        simulation_time_seconds=float(
            _optional_value(data, "simulationTimeSeconds", "simulation_time_seconds", 0.0)
        ),
        step_size_seconds=step_size_seconds,
        model_version=_optional_value(data, "modelVersion", "model_version", MODEL_VERSION),
        parameter_set_id=_optional_value(data, "parameterSetId", "parameter_set_id", ""),
        trace_id=_optional_value(data, "traceId", "trace_id", f"legacy-tick-{tick}"),
        trains=[
            vehicle_physics_input_from_dict(train, step_size_seconds)
            for train in data.get("trains", [])
        ],
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
        "motorSpeedRpm": output.motor_speed_rpm,
        "interpolatedTractionTorqueNmPerMotor": output.interpolated_traction_torque_nm_per_motor,
        "interpolatedBrakeTorqueNmPerMotor": output.interpolated_brake_torque_nm_per_motor,
        "airBrakeForceNewtons": output.air_brake_force_newtons,
        "mechanicalTractionPowerWatts": output.mechanical_traction_power_watts,
        "tractionPowerWatts": output.traction_power_watts,
        "railCurrentAmps": output.rail_current_amps,
        "mechanicalRegenPowerWatts": output.mechanical_regen_power_watts,
        "regenPowerWatts": output.regen_power_watts,
        "energyConsumedKwh": output.energy_consumed_kwh,
        "energyRegeneratedKwh": output.energy_regenerated_kwh,
        "faultCode": output.fault_code,
        "instanceState": output.instance_state,
        "dataQuality": output.data_quality,
        "fmiStatus": output.fmi_status,
    }


def train_step_error_to_dict(error: TrainStepError) -> dict[str, Any]:
    return {
        "trainId": error.train_id,
        "faultCode": error.fault_code,
        "message": error.message,
        "instanceState": error.instance_state,
        "dataQuality": error.data_quality,
        "fmiStatus": error.fmi_status,
    }


def step_fleet_response_to_dict(response: StepFleetResponse) -> dict[str, Any]:
    return {
        "tick": response.tick,
        "modelVersion": response.model_version,
        "parameterSetId": response.parameter_set_id,
        "traceId": response.trace_id,
        "trainOutputs": [
            vehicle_physics_output_to_dict(output) for output in response.train_outputs
        ],
        "trainErrors": [
            train_step_error_to_dict(error) for error in response.train_errors
        ],
    }
