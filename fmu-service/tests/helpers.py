from __future__ import annotations

from dataclasses import replace

from app.fmu_manager import FmuManager
from app.schemas import StepFleetRequest, VehiclePhysicsInput


def train_input(
    train_id: str,
    lifecycle_command: str = "INIT",
    **changes: object,
) -> VehiclePhysicsInput:
    value = VehiclePhysicsInput(
        train_id=train_id,
        lifecycle_command=lifecycle_command,
        section_id="P-TEST",
        position_meters=0.0,
        speed_meters_per_second=10.0,
        train_mass_kg=220_000.0,
        traction_command=0.0,
        brake_command=0.0,
        emergency_brake_command=False,
        speed_limit_meters_per_second=30.0,
        movement_authority_distance_meters=1_000.0,
        gradient=0.0,
        curve_radius_meters=1_000.0,
        rail_voltage=1_500.0,
        power_available_watts=4_000_000.0,
        regen_power_available_watts=4_000_000.0,
        current_collection_available=True,
        door_closed=True,
        adhesion_coefficient=0.9,
        previous_energy_consumed_kwh=0.0,
        previous_energy_regenerated_kwh=0.0,
        delta_seconds=0.1,
        dynamics_state="COASTING",
        dynamics_constraint_reason="NONE",
        station_distance_meters=2_000.0,
        stopping_distance_meters=150.0,
    )
    return replace(value, **changes)


def fleet_request(
    manager: FmuManager,
    tick: int,
    simulation_time_seconds: float,
    trains: list[VehiclePhysicsInput],
    *,
    trace_id: str | None = None,
    model_version: str | None = None,
    parameter_set_id: str | None = None,
    step_size_seconds: float = 0.1,
) -> StepFleetRequest:
    return StepFleetRequest(
        tick=tick,
        simulation_time_seconds=simulation_time_seconds,
        step_size_seconds=step_size_seconds,
        model_version=model_version or manager.model_version,
        parameter_set_id=parameter_set_id or manager.parameter_set_id,
        trace_id=trace_id or f"test-tick-{tick}",
        trains=trains,
    )
