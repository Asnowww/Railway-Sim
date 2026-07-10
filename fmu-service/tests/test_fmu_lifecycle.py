from __future__ import annotations

from dataclasses import replace
from unittest.mock import patch

import pytest

from app.fmu_manager import FmuManager, FmuProtocolError
from app.train_fmu_instance import FmuExecutionError

from .helpers import fleet_request, train_input


@pytest.fixture
def manager():
    value = FmuManager()
    try:
        yield value
    finally:
        value.close()


def assert_protocol_error(
    expected_status: int,
    expected_code: str,
    callback,
) -> None:
    with pytest.raises(FmuProtocolError) as caught:
        callback()
    assert caught.value.status_code == expected_status
    assert caught.value.error_code == expected_code


def test_tick_idempotency_conflict_and_out_of_order(manager: FmuManager) -> None:
    init = fleet_request(manager, 1, 0.0, [train_input("TICK")])
    first = manager.step_fleet(init)
    time_after_first = manager._instances["TICK"].current_time_seconds
    repeated = manager.step_fleet(init)

    assert repeated == first
    assert manager._instances["TICK"].current_time_seconds == time_after_first
    assert manager.health()["duplicateTickCount"] == 1

    conflicting = fleet_request(
        manager,
        1,
        0.0,
        [replace(train_input("TICK"), traction_command=0.5)],
        trace_id="different-payload",
    )
    assert_protocol_error(
        409,
        "FMU_TICK_CONFLICT",
        lambda: manager.step_fleet(conflicting),
    )
    assert manager._instances["TICK"].current_time_seconds == time_after_first

    step = fleet_request(
        manager,
        2,
        0.1,
        [train_input("TICK", "STEP")],
    )
    manager.step_fleet(step)
    out_of_order = fleet_request(
        manager,
        0,
        0.2,
        [train_input("TICK", "STEP")],
    )
    assert_protocol_error(
        409,
        "FMU_TICK_OUT_OF_ORDER",
        lambda: manager.step_fleet(out_of_order),
    )


def test_batch_preflight_rejects_without_advancing_any_instance(
    manager: FmuManager,
) -> None:
    manager.step_fleet(
        fleet_request(manager, 1, 0.0, [train_input("KNOWN-A"), train_input("KNOWN-B")])
    )
    times_before = {
        train_id: instance.current_time_seconds
        for train_id, instance in manager._instances.items()
    }

    mixed = fleet_request(
        manager,
        2,
        0.1,
        [train_input("KNOWN-A", "STEP"), train_input("UNKNOWN", "STEP")],
    )
    assert_protocol_error(
        409,
        "FMU_INSTANCE_NOT_FOUND",
        lambda: manager.step_fleet(mixed),
    )
    assert {
        train_id: instance.current_time_seconds
        for train_id, instance in manager._instances.items()
    } == times_before

    duplicate = fleet_request(
        manager,
        2,
        0.1,
        [train_input("KNOWN-A", "STEP"), train_input("KNOWN-A", "STEP")],
    )
    assert_protocol_error(
        400,
        "DUPLICATE_TRAIN_ID",
        lambda: manager.step_fleet(duplicate),
    )
    assert manager._instances["KNOWN-A"].current_time_seconds == times_before["KNOWN-A"]


def test_model_parameter_step_and_time_mismatch_are_rejected(
    manager: FmuManager,
) -> None:
    init_train = train_input("VALIDATION")
    assert_protocol_error(
        409,
        "FMU_MODEL_VERSION_MISMATCH",
        lambda: manager.step_fleet(
            fleet_request(
                manager,
                1,
                0.0,
                [init_train],
                model_version="wrong/9.9",
            )
        ),
    )
    assert_protocol_error(
        409,
        "FMU_PARAMETER_SET_MISMATCH",
        lambda: manager.step_fleet(
            fleet_request(
                manager,
                1,
                0.0,
                [init_train],
                parameter_set_id="sha256:" + "0" * 64,
            )
        ),
    )
    assert_protocol_error(
        400,
        "INVALID_STEP_SIZE",
        lambda: manager.step_fleet(
            fleet_request(
                manager,
                1,
                0.0,
                [init_train],
                step_size_seconds=0.2,
            )
        ),
    )

    manager.step_fleet(fleet_request(manager, 1, 0.0, [init_train]))
    wrong_time = fleet_request(
        manager,
        2,
        0.3,
        [train_input("VALIDATION", "STEP")],
    )
    assert_protocol_error(
        409,
        "FMU_SIMULATION_TIME_CONFLICT",
        lambda: manager.step_fleet(wrong_time),
    )


def test_reset_resync_delete_and_reset_all(manager: FmuManager) -> None:
    manager.step_fleet(fleet_request(manager, 1, 0.0, [train_input("LIFE")]))
    manager.step_fleet(
        fleet_request(manager, 2, 0.1, [train_input("LIFE", "STEP")])
    )

    reset = train_input(
        "LIFE",
        "RESET",
        position_meters=500.0,
        speed_meters_per_second=2.0,
        previous_energy_consumed_kwh=3.0,
        previous_energy_regenerated_kwh=1.0,
    )
    reset_output = manager.step_fleet(fleet_request(manager, 3, 5.0, [reset])).train_outputs[0]
    assert reset_output.new_position_meters >= 500.0
    assert reset_output.energy_consumed_kwh >= 3.0
    assert reset_output.energy_regenerated_kwh >= 1.0

    resync = replace(
        reset,
        lifecycle_command="RESYNC",
        position_meters=900.0,
        speed_meters_per_second=5.0,
        previous_energy_consumed_kwh=7.0,
        previous_energy_regenerated_kwh=2.0,
    )
    resync_output = manager.step_fleet(
        fleet_request(manager, 4, 9.0, [resync])
    ).train_outputs[0]
    assert resync_output.new_position_meters >= 900.0
    assert resync_output.energy_consumed_kwh >= 7.0
    assert resync_output.energy_regenerated_kwh >= 2.0

    manager.delete_instance("LIFE")
    assert_protocol_error(
        409,
        "FMU_INSTANCE_NOT_FOUND",
        lambda: manager.step_fleet(
            fleet_request(manager, 5, 9.1, [train_input("LIFE", "STEP")])
        ),
    )

    manager.step_fleet(
        fleet_request(manager, 5, 0.0, [train_input("NEW-A"), train_input("NEW-B")])
    )
    result = manager.reset_all()
    assert result["resetInstanceCount"] == 2
    assert manager.health()["instanceCount"] == 0


def test_resync_rebuilds_unknown_instance_after_service_restart(manager: FmuManager) -> None:
    resync = train_input(
        "RESTARTED",
        "RESYNC",
        position_meters=1200.0,
        speed_meters_per_second=6.0,
        previous_energy_consumed_kwh=8.0,
        previous_energy_regenerated_kwh=2.0,
    )

    output = manager.step_fleet(fleet_request(manager, 10, 12.0, [resync])).train_outputs[0]

    assert output.new_position_meters >= 1200.0
    assert output.energy_consumed_kwh >= 8.0
    assert output.energy_regenerated_kwh >= 2.0
    assert manager.health()["instanceCount"] == 1


def test_single_instance_failure_is_isolated_and_requires_resync(
    manager: FmuManager,
) -> None:
    manager.step_fleet(
        fleet_request(manager, 1, 0.0, [train_input("GOOD"), train_input("FAIL")])
    )
    failed_instance = manager._instances["FAIL"]
    step = fleet_request(
        manager,
        2,
        0.1,
        [train_input("GOOD", "STEP"), train_input("FAIL", "STEP")],
    )
    with patch.object(
        failed_instance,
        "step",
        side_effect=FmuExecutionError("injected fmi2DoStep error", "ERROR"),
    ):
        response = manager.step_fleet(step)

    assert [output.train_id for output in response.train_outputs] == ["GOOD"]
    assert len(response.train_errors) == 1
    assert response.train_errors[0].train_id == "FAIL"
    assert response.train_errors[0].fault_code == "FMU_STEP_FAILED"
    assert manager._instances["FAIL"].state == "FAILED"
    assert manager._instances["GOOD"].state == "ACTIVE"

    assert_protocol_error(
        409,
        "FMU_INSTANCE_FAILED",
        lambda: manager.step_fleet(
            fleet_request(manager, 3, 0.2, [train_input("FAIL", "STEP")])
        ),
    )

    recovery = manager.step_fleet(
        fleet_request(
            manager,
            3,
            0.2,
            [
                train_input("GOOD", "STEP"),
                train_input(
                    "FAIL",
                    "RESYNC",
                    position_meters=200.0,
                    speed_meters_per_second=8.0,
                ),
            ],
        )
    )
    assert not recovery.train_errors
    assert manager._instances["FAIL"].state == "ACTIVE"
    assert manager._instances["GOOD"].current_time_seconds == pytest.approx(0.3)
    assert manager._instances["FAIL"].current_time_seconds == pytest.approx(0.3)
