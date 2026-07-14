import json
import os
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator
import yaml

from app.input_mapper import InputMapper


SERVICE_ROOT = Path(__file__).resolve().parents[1]
PROJECT_ROOT = SERVICE_ROOT.parent
CONTRACT_ROOT = SERVICE_ROOT / "contracts"
FMU_MAPPING_PATH = Path(
    os.environ.get("FMU_MAPPING_PATH", PROJECT_ROOT / "config" / "fmu_mapping.yaml")
)


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


class FmuContractTests(unittest.TestCase):
    def test_examples_conform_to_frozen_json_schemas(self) -> None:
        request_schema = load_json(CONTRACT_ROOT / "step-fleet-request.schema.json")
        response_schema = load_json(CONTRACT_ROOT / "step-fleet-response.schema.json")
        request = load_json(CONTRACT_ROOT / "examples" / "step-fleet-request.example.json")
        response = load_json(CONTRACT_ROOT / "examples" / "step-fleet-response.example.json")
        error = load_json(CONTRACT_ROOT / "examples" / "step-fleet-error.example.json")

        Draft202012Validator(request_schema).validate(request)
        Draft202012Validator(response_schema).validate(response)
        Draft202012Validator(response_schema).validate(error)

    def test_python_mapper_consumes_frozen_request(self) -> None:
        request = load_json(CONTRACT_ROOT / "examples" / "step-fleet-request.example.json")

        mapped = InputMapper().from_payload(request)

        self.assertEqual(12001, mapped.tick)
        self.assertEqual(0.02, mapped.step_size_seconds)
        self.assertEqual("STEP", mapped.trains[0].lifecycle_command)
        self.assertEqual(3_700_000.0, mapped.trains[0].power_available_watts)
        self.assertEqual(0.0, mapped.trains[0].regen_power_available_watts)
        self.assertTrue(mapped.trains[0].current_collection_available)

    def test_mapping_separates_fmi_inputs_metadata_and_power_sides(self) -> None:
        mapping = yaml.safe_load(
            FMU_MAPPING_PATH.read_text(encoding="utf-8")
        )

        self.assertEqual(
            {"parameters", "initialState", "stepInputs", "metadata", "outputs"},
            {
                key
                for key in mapping
                if key in {"parameters", "initialState", "stepInputs", "metadata", "outputs"}
            },
        )
        self.assertNotIn("movementAuthorityDistanceMeters", mapping["stepInputs"])
        self.assertNotIn("speedLimitMetersPerSecond", mapping["stepInputs"])
        self.assertNotIn("curveRadiusMeters", mapping["stepInputs"])
        self.assertEqual("Boolean", mapping["stepInputs"]["doorClosed"]["type"])
        self.assertEqual("Boolean", mapping["stepInputs"]["emergencyBrakeCommand"]["type"])
        self.assertEqual("grid", mapping["outputs"]["tractionPowerWatts"]["powerSide"])
        self.assertEqual("grid", mapping["outputs"]["regenPowerWatts"]["powerSide"])
        self.assertEqual("Integer", mapping["outputs"]["faultCode"]["type"])


if __name__ == "__main__":
    unittest.main()
