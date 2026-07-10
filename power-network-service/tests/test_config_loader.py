from __future__ import annotations

import unittest
from pathlib import Path

from app.config_loader import load_power_config
from app.manager import PowerNetworkModel


class PowerConfigLoaderTests(unittest.TestCase):
    def test_project_yaml_bootstraps_five_1500_volt_sections(self) -> None:
        source = Path(__file__).resolve().parents[2] / "config" / "power_third_rail.yaml"
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
