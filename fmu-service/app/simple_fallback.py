from .schemas import VehiclePhysicsInput, VehiclePhysicsOutput


class SimpleFallbackModel:
    max_traction_force_newtons = 240_000.0
    max_service_brake_force_newtons = 220_000.0
    max_emergency_brake_force_newtons = 300_000.0
    nominal_power_watts = 3_200_000.0
    traction_efficiency = 0.88
    regen_efficiency = 0.35
    gravity = 9.81

    def step(self, train: VehiclePhysicsInput) -> VehiclePhysicsOutput:
        dt = max(train.delta_seconds, 0.001)
        speed = max(train.speed_meters_per_second, 0.0)
        mass = max(train.train_mass_kg, 1.0)
        power_factor = 0.0
        if train.rail_voltage > 0 and train.power_available_watts > 0:
            power_factor = min(1.0, train.power_available_watts / self.nominal_power_watts)

        traction_force = (
            self.max_traction_force_newtons
            * min(1.0, max(0.0, train.traction_command))
            * power_factor
            * min(1.0, max(0.2, train.adhesion_coefficient))
        )
        brake_force = (
            self.max_emergency_brake_force_newtons
            if train.emergency_brake_command
            else self.max_service_brake_force_newtons * min(1.0, max(0.0, train.brake_command))
        )
        resistance_force = 1_800.0 + 45.0 * speed + 3.2 * speed * speed
        gradient_force = mass * self.gravity * train.gradient
        acceleration = max(-1.3, min(1.0, (traction_force - brake_force - resistance_force - gradient_force) / mass))

        new_speed = max(0.0, speed + acceleration * dt)
        new_position = train.position_meters + (speed + new_speed) * 0.5 * dt
        traction_power = min(
            train.power_available_watts,
            max(0.0, traction_force * max(new_speed, 0.1) / self.traction_efficiency),
        )
        rail_current = traction_power / train.rail_voltage if train.rail_voltage > 1 else 0.0
        regen_brake_force = brake_force * 0.45 if brake_force > 0 and speed > 0 else 0.0
        regen_power = regen_brake_force * speed * self.regen_efficiency

        return VehiclePhysicsOutput(
            train_id=train.train_id,
            new_position_meters=new_position,
            new_speed_meters_per_second=new_speed,
            acceleration_meters_per_second_squared=acceleration,
            traction_force_newtons=traction_force,
            brake_force_newtons=brake_force,
            regen_brake_force_newtons=regen_brake_force,
            traction_power_watts=traction_power,
            rail_current_amps=rail_current,
            regen_power_watts=regen_power,
            energy_consumed_kwh=train.previous_energy_consumed_kwh + traction_power * dt / 3_600_000.0,
            energy_regenerated_kwh=train.previous_energy_regenerated_kwh + regen_power * dt / 3_600_000.0,
            fault_code="THIRD_RAIL_DEENERGIZED" if train.rail_voltage <= 0 else "OK",
        )
