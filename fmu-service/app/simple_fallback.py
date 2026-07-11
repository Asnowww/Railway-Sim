from __future__ import annotations

import math

from .schemas import VehiclePhysicsInput, VehiclePhysicsOutput
from .vehicle_parameters import VehicleParameters, load_vehicle_parameters


class SimpleFallbackModel:
    """Offline curve-v2 reference; production fallback remains the 9300 Java executor."""

    gravity = 9.81
    speed_floor_meters_per_second = 0.5

    def __init__(self, parameters: VehicleParameters | None = None) -> None:
        self.parameters = parameters or load_vehicle_parameters()

    @staticmethod
    def interpolate(x: float, x_grid: tuple[float, ...], y_grid: tuple[float, ...]) -> float:
        if x <= x_grid[0]:
            return y_grid[0]
        if x >= x_grid[-1]:
            return y_grid[-1]
        for index in range(len(x_grid) - 1):
            if x_grid[index] <= x < x_grid[index + 1]:
                ratio = (x - x_grid[index]) / (x_grid[index + 1] - x_grid[index])
                return y_grid[index] + (y_grid[index + 1] - y_grid[index]) * ratio
        raise RuntimeError(f"curve interpolation did not find an interval for {x}")

    def step(self, train: VehiclePhysicsInput) -> VehiclePhysicsOutput:
        params = self.parameters
        dt = max(train.delta_seconds, 0.001)
        speed = max(train.speed_meters_per_second, 0.0)
        mass = train.train_mass_kg
        if not 0 < mass <= params.formation.hard_mass_limit_kg:
            raise ValueError("train_mass_kg exceeds the configured hard limit")
        drivetrain = params.drivetrain
        curves = params.curves
        motor_speed_rpm = (
            speed / drivetrain.wheel_radius_meters
            * drivetrain.gear_ratio
            * 60.0
            / (2.0 * math.pi)
        )
        traction_torque = self.interpolate(
            motor_speed_rpm, curves.speed_rpm, curves.traction_torque_nm_per_motor
        )
        brake_torque = self.interpolate(
            motor_speed_rpm, curves.speed_rpm, curves.brake_torque_nm_per_motor
        )
        force_factor = (
            drivetrain.motor_count * drivetrain.gear_ratio / drivetrain.wheel_radius_meters
        )
        curve_traction_force = traction_torque * force_factor
        curve_brake_force = brake_torque * force_factor
        traction_available = (
            train.current_collection_available
            and train.rail_voltage > params.power.cutoff_voltage
            and train.power_available_watts > 0
        )
        grid_available = train.power_available_watts if traction_available else 0.0
        command_force = curve_traction_force * min(1.0, max(0.0, train.traction_command))
        supply_force = (
            grid_available * drivetrain.traction_total_efficiency
            / max(speed, self.speed_floor_meters_per_second)
        )
        adhesion_force = min(1.0, max(0.2, train.adhesion_coefficient)) * mass * self.gravity
        traction_force = (
            min(command_force, supply_force, adhesion_force)
            if train.door_closed and not train.emergency_brake_command
            else 0.0
        )
        brake_force = (
            mass * params.brake.emergency_deceleration_mps2
            if train.emergency_brake_command
            else mass * params.brake.service_deceleration_mps2
            * min(1.0, max(0.0, train.brake_command))
        )
        regen_candidate_force = (
            min(brake_force, curve_brake_force)
            if not train.emergency_brake_command and brake_force > 0 and speed > 0
            else 0.0
        )
        regen_mechanical_limit = (
            train.regen_power_available_watts / drivetrain.regen_total_efficiency
            if train.regen_power_available_watts > 0 else 0.0
        )
        mechanical_regen_power = min(regen_candidate_force * speed, regen_mechanical_limit)
        regen_brake_force = (
            min(
                regen_candidate_force,
                mechanical_regen_power / max(speed, self.speed_floor_meters_per_second),
            )
            if mechanical_regen_power > 0 else 0.0
        )
        mechanical_regen_power = regen_brake_force * speed
        regen_power = mechanical_regen_power * drivetrain.regen_total_efficiency
        air_brake_force = max(0.0, brake_force - regen_brake_force)
        resistance_force = params.resistance.force_newtons(
            mass, params.formation.axle_count, len(params.formation.order), speed
        )
        gradient_force = mass * self.gravity * train.gradient
        acceleration = max(
            -params.brake.emergency_deceleration_mps2,
            min(
                params.brake.service_deceleration_mps2,
                (traction_force - brake_force - resistance_force - gradient_force) / mass,
            ),
        )
        new_speed = max(0.0, speed + acceleration * dt)
        new_position = train.position_meters + (speed + new_speed) * 0.5 * dt
        mechanical_traction_power = traction_force * speed
        traction_power = (
            min(grid_available, mechanical_traction_power / drivetrain.traction_total_efficiency)
            if mechanical_traction_power > 0 else 0.0
        )
        rail_current = traction_power / train.rail_voltage if train.rail_voltage > 1 else 0.0
        fault_code = self._fault_code(train)
        return VehiclePhysicsOutput(
            train_id=train.train_id,
            new_position_meters=new_position,
            new_speed_meters_per_second=new_speed,
            acceleration_meters_per_second_squared=acceleration,
            traction_force_newtons=traction_force,
            brake_force_newtons=brake_force,
            regen_brake_force_newtons=regen_brake_force,
            motor_speed_rpm=motor_speed_rpm,
            interpolated_traction_torque_nm_per_motor=traction_torque,
            interpolated_brake_torque_nm_per_motor=brake_torque,
            air_brake_force_newtons=air_brake_force,
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
