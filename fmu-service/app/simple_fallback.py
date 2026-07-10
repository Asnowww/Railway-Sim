from __future__ import annotations

from .schemas import VehiclePhysicsInput, VehiclePhysicsOutput
from .vehicle_parameters import VehicleParameters, load_vehicle_parameters


class SimpleFallbackModel:
    gravity = 9.81
    speed_floor_meters_per_second = 0.5

    def __init__(self, parameters: VehicleParameters | None = None) -> None:
        self.parameters = parameters or load_vehicle_parameters()

    def step(self, train: VehiclePhysicsInput) -> VehiclePhysicsOutput:
        params = self.parameters
        dt = max(train.delta_seconds, 0.001)
        speed = max(train.speed_meters_per_second, 0.0)
        mass = max(train.train_mass_kg, 1.0)
        traction_available = (
            train.current_collection_available
            and train.rail_voltage > params.power.cutoff_voltage
            and train.power_available_watts > 0
        )
        grid_power_available = train.power_available_watts if traction_available else 0.0
        mechanical_power_limit = min(
            params.traction.max_power_watts,
            grid_power_available * params.traction.efficiency,
        )
        command_force = (
            params.traction.max_traction_force_newtons
            * min(1.0, max(0.0, train.traction_command))
        )
        power_limited_force = mechanical_power_limit / max(
            speed, self.speed_floor_meters_per_second
        )
        adhesion_limited_force = (
            min(1.0, max(0.2, train.adhesion_coefficient))
            * mass
            * self.gravity
        )
        traction_force = (
            min(command_force, power_limited_force, adhesion_limited_force)
            if train.door_closed and not train.emergency_brake_command
            else 0.0
        )
        brake_force = (
            params.brake.max_emergency_brake_force_newtons
            if train.emergency_brake_command
            else params.brake.max_service_brake_force_newtons
            * min(1.0, max(0.0, train.brake_command))
        )
        resistance_force = (
            params.resistance.davis_a
            + params.resistance.davis_b * speed
            + params.resistance.davis_c * speed * speed
        )
        gradient_force = mass * self.gravity * train.gradient
        acceleration = max(
            -1.3,
            min(
                1.0,
                (traction_force - brake_force - resistance_force - gradient_force)
                / mass,
            ),
        )
        new_speed = max(0.0, speed + acceleration * dt)
        mean_speed = (speed + new_speed) * 0.5
        new_position = train.position_meters + mean_speed * dt
        mechanical_traction_power = min(
            mechanical_power_limit,
            traction_force * mean_speed,
        )
        traction_power = (
            min(
                grid_power_available,
                mechanical_traction_power / params.traction.efficiency,
            )
            if mechanical_traction_power > 0
            else 0.0
        )
        rail_current = traction_power / train.rail_voltage if train.rail_voltage > 1 else 0.0

        regen_candidate_force = (
            brake_force * params.brake.regen_brake_ratio
            if brake_force > 0 and speed > 0
            else 0.0
        )
        regen_candidate_mechanical_power = regen_candidate_force * speed
        regen_grid_mechanical_limit = (
            train.regen_power_available_watts / params.brake.regen_efficiency
            if train.regen_power_available_watts > 0
            else 0.0
        )
        mechanical_regen_power = min(
            regen_candidate_mechanical_power,
            params.traction.max_power_watts,
            regen_grid_mechanical_limit,
        )
        regen_brake_force = (
            min(
                regen_candidate_force,
                mechanical_regen_power / max(speed, self.speed_floor_meters_per_second),
            )
            if mechanical_regen_power > 0
            else 0.0
        )
        mechanical_regen_power = regen_brake_force * speed
        regen_power = mechanical_regen_power * params.brake.regen_efficiency
        fault_code = self._fault_code(train)

        return VehiclePhysicsOutput(
            train_id=train.train_id,
            new_position_meters=new_position,
            new_speed_meters_per_second=new_speed,
            acceleration_meters_per_second_squared=acceleration,
            traction_force_newtons=traction_force,
            brake_force_newtons=brake_force,
            regen_brake_force_newtons=regen_brake_force,
            mechanical_traction_power_watts=mechanical_traction_power,
            traction_power_watts=traction_power,
            rail_current_amps=rail_current,
            mechanical_regen_power_watts=mechanical_regen_power,
            regen_power_watts=regen_power,
            energy_consumed_kwh=train.previous_energy_consumed_kwh
            + traction_power * dt / 3_600_000.0,
            energy_regenerated_kwh=train.previous_energy_regenerated_kwh
            + regen_power * dt / 3_600_000.0,
            fault_code=fault_code,
            data_quality="GOOD" if fault_code == "OK" else "DEGRADED",
        )

    def _fault_code(self, train: VehiclePhysicsInput) -> str:
        if not train.door_closed:
            return "DOOR_NOT_LOCKED"
        if train.emergency_brake_command:
            return "ATP_BRAKE"
        if (
            not train.current_collection_available
            or train.rail_voltage <= self.parameters.power.cutoff_voltage
            or train.power_available_watts <= 0
        ):
            return "CURRENT_COLLECTION_LOST"
        if train.rail_voltage < self.parameters.power.min_voltage:
            return "LOW_VOLTAGE"
        return "OK"
