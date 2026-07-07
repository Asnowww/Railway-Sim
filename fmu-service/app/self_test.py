from .fmu_manager import FmuManager
from .schemas import StepFleetRequest, VehiclePhysicsInput


def main() -> None:
    request = StepFleetRequest(
        sim_time="1970-01-01T00:00:00Z",
        delta_seconds=0.1,
        trains=[
            VehiclePhysicsInput(
                train_id="TR-TEST",
                position_meters=100.0,
                speed_meters_per_second=0.0,
                train_mass_kg=220_000.0,
                traction_command=0.8,
                brake_command=0.0,
                emergency_brake_command=False,
                speed_limit_meters_per_second=20.0,
                movement_authority_distance_meters=1000.0,
                gradient=0.0,
                curve_radius_meters=1000.0,
                rail_voltage=1500.0,
                power_available_watts=3_000_000.0,
                door_closed=True,
                adhesion_coefficient=0.9,
                previous_energy_consumed_kwh=0.0,
                previous_energy_regenerated_kwh=0.0,
                delta_seconds=0.1,
            )
        ],
    )
    response = FmuManager().step_fleet(request)
    assert len(response.train_outputs) == 1
    output = response.train_outputs[0]
    assert output.train_id == "TR-TEST"
    assert output.new_position_meters >= 100.0
    assert output.fault_code == "OK"


if __name__ == "__main__":
    main()
