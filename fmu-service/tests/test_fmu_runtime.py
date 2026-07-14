from __future__ import annotations

from dataclasses import replace
import math

import pytest

from app.fmu_manager import FmuManager
from app.simple_fallback import SimpleFallbackModel
from app.vehicle_parameters import load_vehicle_parameters

from .helpers import fleet_request, train_input


@pytest.fixture
def manager():
    value = FmuManager()
    try:
        yield value
    finally:
        value.close()


def test_fmu_artifact_and_mapping_are_valid(manager: FmuManager) -> None:
    metadata = manager.metadata()
    validation = manager.validate_fmu()

    assert metadata["fmiVersion"] == "2.0"
    assert metadata["fmuType"] == "CoSimulation"
    assert metadata["targetPlatform"] == "linux/amd64"
    assert metadata["runtimePlatform"] == "linux/x86_64"
    assert metadata["fmpyVersion"] == "0.3.30"
    assert metadata["fastapiVersion"] == "0.139.0"
    assert metadata["uvicornVersion"] == "0.51.0"
    assert metadata["variableValidation"]["status"] == "VALID"
    assert metadata["parameterSchemaVersion"] == "2"
    assert metadata["curvePointCount"] == 52
    assert metadata["curveSetId"].startswith("sha256:")
    assert metadata["lengthMeters"] == 118.0
    assert metadata["variableValidation"]["checkedVariableCount"] == 206
    assert validation["status"] == "VALID"
    assert validation["parameterSetId"] == manager.parameter_set_id


def test_all_curve_knots_and_midpoints_match_fmu_diagnostics(manager: FmuManager) -> None:
    parameters = load_vehicle_parameters()
    speed_rpm = parameters.curves.speed_rpm
    sample_rpm = list(speed_rpm)
    sample_rpm.extend(
        (speed_rpm[index] + speed_rpm[index + 1]) * 0.5
        for index in range(len(speed_rpm) - 1)
    )
    trains = []
    for index, rpm in enumerate(sample_rpm):
        speed_mps = (
            rpm * 2.0 * math.pi / 60.0
            * parameters.drivetrain.wheel_radius_meters
            / parameters.drivetrain.gear_ratio
        )
        trains.append(
            train_input(
                f"CURVE-{index:03d}",
                speed_meters_per_second=speed_mps,
                train_mass_kg=parameters.empty_mass_kg,
                traction_command=0.0,
                brake_command=0.0,
                power_available_watts=10_000_000.0,
            )
        )
    response = manager.step_fleet(fleet_request(manager, 1, 0.0, trains))
    assert not response.train_errors
    assert len(response.train_outputs) == 103
    for output in response.train_outputs:
        expected_traction = SimpleFallbackModel.interpolate(
            output.motor_speed_rpm,
            speed_rpm,
            parameters.curves.traction_torque_nm_per_motor,
        )
        expected_brake = SimpleFallbackModel.interpolate(
            output.motor_speed_rpm,
            speed_rpm,
            parameters.curves.brake_torque_nm_per_motor,
        )
        assert output.interpolated_traction_torque_nm_per_motor == pytest.approx(
            expected_traction, rel=5e-6, abs=1e-6
        )
        assert output.interpolated_brake_torque_nm_per_motor == pytest.approx(
            expected_brake, rel=5e-6, abs=1e-6
        )


def test_real_fmu_physics_scenarios(manager: FmuManager) -> None:
    trains = [
        train_input("COAST"),
        train_input(
            "LOW_SPEED_TRACTION",
            speed_meters_per_second=1.0,
            traction_command=1.0,
            dynamics_state="TRACTION",
        ),
        train_input(
            "CONSTANT_POWER",
            speed_meters_per_second=20.0,
            traction_command=1.0,
            power_available_watts=10_000_000.0,
            dynamics_state="TRACTION",
        ),
        train_input(
            "SUPPLY_LIMIT",
            speed_meters_per_second=20.0,
            traction_command=1.0,
            power_available_watts=1_000_000.0,
            dynamics_state="TRACTION",
        ),
        train_input("GRADE", gradient=0.010),
        train_input(
            "SERVICE_BRAKE",
            brake_command=1.0,
            dynamics_state="BRAKING",
        ),
        train_input(
            "REGEN_LIMIT",
            brake_command=1.0,
            regen_power_available_watts=100_000.0,
            dynamics_state="BRAKING",
        ),
        train_input(
            "NO_REGEN",
            brake_command=1.0,
            regen_power_available_watts=0.0,
            dynamics_state="BRAKING",
        ),
        train_input(
            "EMERGENCY",
            traction_command=1.0,
            emergency_brake_command=True,
            dynamics_state="EMERGENCY_BRAKE",
        ),
        train_input(
            "CUTOFF",
            traction_command=1.0,
            rail_voltage=900.0,
            dynamics_state="TRACTION",
        ),
        train_input(
            "LOW_VOLTAGE",
            traction_command=1.0,
            rail_voltage=950.0,
            dynamics_state="TRACTION",
        ),
        train_input(
            "DOOR",
            traction_command=1.0,
            door_closed=False,
            dynamics_state="TRACTION",
        ),
        train_input(
            "ENERGY",
            speed_meters_per_second=20.0,
            traction_command=1.0,
            power_available_watts=10_000_000.0,
            previous_energy_consumed_kwh=12.5,
            dynamics_state="TRACTION",
        ),
    ]
    response = manager.step_fleet(fleet_request(manager, 1, 0.0, trains))
    assert not response.train_errors
    output = {item.train_id: item for item in response.train_outputs}

    coast = output["COAST"]
    coast_speed_kph = coast.new_speed_meters_per_second * 3.6
    expected_coast_resistance = (
        6.4 * 220
        + 130 * 24
        + 0.14 * 220 * coast_speed_kph
        + (0.046 + 0.0065 * 5) * 10.6 * coast_speed_kph**2
    )
    expected_coast_acceleration = -expected_coast_resistance / 220_000
    assert coast.acceleration_meters_per_second_squared == pytest.approx(
        expected_coast_acceleration, rel=0.01
    )
    assert coast.new_speed_meters_per_second < 10.0

    low_speed = output["LOW_SPEED_TRACTION"]
    assert low_speed.traction_force_newtons == pytest.approx(
        1042.9 * 16 * 6.5 / 0.46,
        rel=0.01,
    )

    constant_power = output["CONSTANT_POWER"]
    assert constant_power.mechanical_traction_power_watts <= 4_336_000.0 + 1.0
    assert constant_power.mechanical_traction_power_watts > 3_200_000.0
    assert constant_power.traction_power_watts == pytest.approx(
        constant_power.mechanical_traction_power_watts / 0.882, rel=0.01
    )
    assert constant_power.rail_current_amps == pytest.approx(
        constant_power.traction_power_watts / 1500.0, rel=0.01
    )

    supply_limit = output["SUPPLY_LIMIT"]
    assert supply_limit.mechanical_traction_power_watts <= 882_000.0 + 1.0
    assert supply_limit.traction_power_watts <= 1_000_000.0 + 1.0

    grade = output["GRADE"]
    grade_speed_kph = grade.new_speed_meters_per_second * 3.6
    grade_resistance = (
        6.4 * 220
        + 130 * 24
        + 0.14 * 220 * grade_speed_kph
        + (0.046 + 0.0065 * 5) * 10.6 * grade_speed_kph**2
    )
    expected_grade_acceleration = (
        -grade_resistance - 220_000 * 9.81 * 0.010
    ) / 220_000
    assert grade.acceleration_meters_per_second_squared == pytest.approx(
        expected_grade_acceleration, rel=0.01
    )

    service = output["SERVICE_BRAKE"]
    assert service.brake_force_newtons == pytest.approx(220_000.0, abs=1.0)
    assert service.regen_brake_force_newtons <= service.brake_force_newtons
    assert service.regen_power_watts == pytest.approx(
        service.mechanical_regen_power_watts * 0.802, rel=0.01
    )

    regen_limit = output["REGEN_LIMIT"]
    assert regen_limit.regen_power_watts <= 100_000.0 + 1.0
    assert regen_limit.brake_force_newtons == pytest.approx(220_000.0, abs=1.0)
    no_regen = output["NO_REGEN"]
    assert no_regen.regen_power_watts == pytest.approx(0.0, abs=1e-9)
    assert no_regen.brake_force_newtons == pytest.approx(220_000.0, abs=1.0)

    emergency = output["EMERGENCY"]
    assert emergency.traction_force_newtons == pytest.approx(0.0, abs=1e-9)
    assert emergency.brake_force_newtons == pytest.approx(286_000.0, abs=1.0)
    assert emergency.regen_brake_force_newtons == pytest.approx(0.0, abs=1e-9)
    assert emergency.fault_code == "ATP_BRAKE"

    cutoff = output["CUTOFF"]
    assert cutoff.traction_force_newtons == pytest.approx(0.0, abs=1e-9)
    assert cutoff.fault_code == "CURRENT_COLLECTION_LOST"
    low_voltage = output["LOW_VOLTAGE"]
    assert low_voltage.traction_force_newtons > 0
    assert low_voltage.fault_code == "LOW_VOLTAGE"
    door = output["DOOR"]
    assert door.traction_force_newtons == pytest.approx(0.0, abs=1e-9)
    assert door.fault_code == "DOOR_NOT_LOCKED"

    energy = output["ENERGY"]
    expected_energy_delta = energy.traction_power_watts * 0.02 / 3_600_000.0
    assert energy.energy_consumed_kwh - 12.5 == pytest.approx(
        expected_energy_delta, rel=0.01
    )
    for item in response.train_outputs:
        assert math.isfinite(item.new_position_meters)
        assert item.mechanical_traction_power_watts <= 4_336_000.0 + 1.0


def test_two_persistent_instances_remain_independent_for_100_steps(
    manager: FmuManager,
) -> None:
    a = train_input(
        "TRAIN-A",
        position_meters=0.0,
        speed_meters_per_second=0.0,
        traction_command=1.0,
        dynamics_state="TRACTION",
    )
    b = train_input(
        "TRAIN-B",
        position_meters=1_000.0,
        speed_meters_per_second=15.0,
        power_available_watts=0.0,
        current_collection_available=False,
    )
    response = manager.step_fleet(fleet_request(manager, 1, 0.0, [a, b]))
    outputs = {item.train_id: item for item in response.train_outputs}

    for tick in range(2, 102):
        a_output = outputs["TRAIN-A"]
        b_output = outputs["TRAIN-B"]
        a = replace(
            a,
            lifecycle_command="STEP",
            position_meters=a_output.new_position_meters,
            speed_meters_per_second=a_output.new_speed_meters_per_second,
            previous_energy_consumed_kwh=a_output.energy_consumed_kwh,
            previous_energy_regenerated_kwh=a_output.energy_regenerated_kwh,
        )
        b = replace(
            b,
            lifecycle_command="STEP",
            position_meters=b_output.new_position_meters,
            speed_meters_per_second=b_output.new_speed_meters_per_second,
            previous_energy_consumed_kwh=b_output.energy_consumed_kwh,
            previous_energy_regenerated_kwh=b_output.energy_regenerated_kwh,
        )
        response = manager.step_fleet(
            fleet_request(manager, tick, (tick - 1) * 0.02, [a, b])
        )
        assert not response.train_errors
        outputs = {item.train_id: item for item in response.train_outputs}

    assert outputs["TRAIN-A"].new_position_meters < 1_000.0
    assert outputs["TRAIN-B"].new_position_meters > 1_020.0
    assert outputs["TRAIN-A"].energy_consumed_kwh > 0
    assert outputs["TRAIN-B"].energy_consumed_kwh == pytest.approx(0.0, abs=1e-9)

    b_before_delete = outputs["TRAIN-B"]
    deletion = manager.delete_instance("TRAIN-A")
    assert deletion["instanceState"] == "TERMINATED"
    b = replace(
        b,
        lifecycle_command="STEP",
        position_meters=b_before_delete.new_position_meters,
        speed_meters_per_second=b_before_delete.new_speed_meters_per_second,
    )
    after_delete = manager.step_fleet(fleet_request(manager, 102, 2.02, [b]))
    assert after_delete.train_outputs[0].new_position_meters > b_before_delete.new_position_meters


def test_manager_close_terminates_all_native_instances(manager: FmuManager) -> None:
    manager.step_fleet(
        fleet_request(manager, 1, 0.0, [train_input("CLOSE-A"), train_input("CLOSE-B")])
    )
    instances = list(manager._instances.values())

    manager.close()

    assert all(instance.state == "TERMINATED" for instance in instances)
    assert all(instance._slave is None for instance in instances)
