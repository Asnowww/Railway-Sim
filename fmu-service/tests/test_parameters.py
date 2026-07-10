import hashlib
import os
from pathlib import Path
import tempfile
import unittest

from app.fmu_manager import FmuManager, ParameterSetMismatchError
from app.schemas import MODEL_VERSION, StepFleetRequest
from app.vehicle_parameters import load_vehicle_parameters


SERVICE_ROOT = Path(__file__).resolve().parents[1]
PROJECT_ROOT = SERVICE_ROOT.parent
TRAIN_PARAMS_PATH = Path(
    os.environ.get("TRAIN_PARAMS_PATH", PROJECT_ROOT / "config" / "train_params.yaml")
)


class VehicleParameterTests(unittest.TestCase):
    def test_loads_canonical_yaml_and_hashes_raw_bytes(self) -> None:
        parameters = load_vehicle_parameters(TRAIN_PARAMS_PATH)
        expected = "sha256:" + hashlib.sha256(TRAIN_PARAMS_PATH.read_bytes()).hexdigest()

        self.assertEqual(198_000.0, parameters.empty_mass_kg)
        self.assertEqual(72_000.0, parameters.max_load_mass_kg)
        self.assertEqual(3_200_000.0, parameters.traction.max_power_watts)
        self.assertEqual(0.88, parameters.traction.efficiency)
        self.assertEqual(expected, parameters.parameter_set_id)

    def test_invalid_efficiency_reports_exact_yaml_path(self) -> None:
        invalid_content = TRAIN_PARAMS_PATH.read_text(encoding="utf-8").replace(
            "efficiency: 0.88",
            "efficiency: 0",
        )
        with tempfile.TemporaryDirectory() as temp_directory:
            invalid_path = Path(temp_directory) / "invalid.yaml"
            invalid_path.write_text(invalid_content, encoding="utf-8")

            with self.assertRaisesRegex(ValueError, r"traction\.efficiency"):
                load_vehicle_parameters(invalid_path)

    def test_manager_rejects_mismatched_parameter_set(self) -> None:
        manager = FmuManager(load_vehicle_parameters(TRAIN_PARAMS_PATH))
        try:
            request = StepFleetRequest(
                tick=1,
                simulation_time_seconds=0.1,
                step_size_seconds=0.1,
                model_version=MODEL_VERSION,
                parameter_set_id="sha256:" + "0" * 64,
                trace_id="mismatch-test",
                trains=[],
            )

            with self.assertRaises(ParameterSetMismatchError):
                manager.step_fleet(request)
        finally:
            manager.close()

    def test_unknown_parameter_is_rejected(self) -> None:
        invalid_content = TRAIN_PARAMS_PATH.read_text(encoding="utf-8").replace(
            "  efficiency: 0.88",
            "  efficiency: 0.88\n  unsupportedEfficiency: 0.5",
        )
        with tempfile.TemporaryDirectory() as temp_directory:
            invalid_path = Path(temp_directory) / "unknown.yaml"
            invalid_path.write_text(invalid_content, encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "unsupportedEfficiency"):
                load_vehicle_parameters(invalid_path)


if __name__ == "__main__":
    unittest.main()
