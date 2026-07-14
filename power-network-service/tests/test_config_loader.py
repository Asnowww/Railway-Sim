from __future__ import annotations

import unittest
from pathlib import Path

from app.config_loader import load_power_config, resolve_power_config_path
from app.manager import PowerNetworkModel


class PowerConfigLoaderTests(unittest.TestCase):
    def test_default_path_resolves_project_five_section_config(self) -> None:
        expected = Path(__file__).resolve().parents[2] / "config" / "power_third_rail.yaml"

        self.assertEqual(expected, resolve_power_config_path())

    def test_explicit_path_overrides_default(self) -> None:
        self.assertEqual(Path("/tmp/custom-power.yaml"), resolve_power_config_path(" /tmp/custom-power.yaml "))

    def test_project_yaml_bootstraps_five_1500_volt_sections(self) -> None:
        source = resolve_power_config_path()
        payload, digest = load_power_config(source)
        model = PowerNetworkModel()
        model.bootstrap(payload)

        self.assertEqual(1500.0, model.nominal_dc_voltage)
        self.assertEqual(5, len(model.third_rail_sections))
        self.assertEqual("P01", model.constraints_for_positions([
            {"trainId": "TR-001", "positionMeters": 100.0}
        ])[0]["sectionId"])
        self.assertTrue(digest.startswith("sha256:"))


if __name__ == "__main__":
    unittest.main()
