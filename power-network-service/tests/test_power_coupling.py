from __future__ import annotations

import copy
import unittest

from app.manager import PowerNetworkModel
from app.main import TimedPowerStepPayload, model as api_model, step as api_step
from app.self_test import central_bootstrap_payload
from fastapi import HTTPException


class PowerCouplingTests(unittest.TestCase):
    def setUp(self) -> None:
        self.model = PowerNetworkModel()
        self.model.bootstrap(two_section_payload())

    def test_same_section_load_changes_all_train_constraints_and_splits_regen_budget(self) -> None:
        positions = [
            {"trainId": "TR-A", "positionMeters": 200.0},
            {"trainId": "TR-B", "positionMeters": 700.0},
        ]
        unloaded_constraints = self.model.constraints_for_positions(positions)
        unloaded_voltage = unloaded_constraints[0]["railVoltage"]
        unloaded_b = next(item for item in unloaded_constraints if item["trainId"] == "TR-B")

        response = self.model.step(
            1,
            load_payload("P01", ["TR-A", "TR-B"], 1_800_000, 0, 1_200),
            positions,
        )

        constraints = response["powerConstraints"]
        self.assertEqual(1, response["tick"])
        self.assertTrue(all(item["railVoltage"] < unloaded_voltage for item in constraints))
        self.assertEqual(constraints[0]["railVoltage"], constraints[1]["railVoltage"])
        self.assertEqual(900_000.0, constraints[0]["regenPowerAvailableWatts"])
        self.assertEqual(900_000.0, constraints[1]["regenPowerAvailableWatts"])
        loaded_b = next(item for item in constraints if item["trainId"] == "TR-B")
        curve_power_limit = 4_336_000
        curve_force_limit = 1042.9 * 16 * 6.5 / 0.46
        baseline_mechanical_power = min(curve_power_limit, unloaded_b["powerAvailableWatts"] * 0.882)
        next_step_mechanical_power = min(curve_power_limit, loaded_b["powerAvailableWatts"] * 0.882)
        baseline_force_at_15_mps = min(curve_force_limit, baseline_mechanical_power / 15.0)
        next_step_force_at_15_mps = min(curve_force_limit, next_step_mechanical_power / 15.0)
        self.assertLess(next_step_mechanical_power, baseline_mechanical_power)
        self.assertLess(next_step_force_at_15_mps, baseline_force_at_15_mps)

    def test_reference_peak_traction_is_limited_by_real_section_current_capacity(self) -> None:
        positions = [{"trainId": "TR-PEAK", "positionMeters": 200.0}]
        response = self.model.step(
            1,
            load_payload("P01", ["TR-PEAK"], 4_916_250.0, 0.0, 3_277.5),
            positions,
        )
        constraint = response["powerConstraints"][0]
        self.assertLess(constraint["railVoltage"], 1500.0)
        self.assertLess(constraint["powerAvailableWatts"], 4_916_250.0)
        self.assertLess(constraint["powerAvailableWatts"] * 0.882, 4_336_000.0)

    def test_reference_peak_regen_records_unabsorbed_power(self) -> None:
        response = self.model.step(
            1,
            load_payload("P01", ["TR-PEAK"], 4_916_250.0, 5_464_350.0, 0.0),
            [{"trainId": "TR-PEAK", "positionMeters": 200.0}],
        )
        section = next(
            item for item in response["thirdRailSections"]
            if item["powerSectionId"] == "P01"
        )
        self.assertEqual(4_916_250.0, section["absorbedRegenWatts"])
        self.assertEqual(548_100.0, section["unabsorbedRegenWatts"])
        self.assertEqual(4_916_250.0, section["regenBudgetWatts"])

    def test_different_section_isolation(self) -> None:
        positions = [
            {"trainId": "TR-A", "positionMeters": 200.0},
            {"trainId": "TR-C", "positionMeters": 1_500.0},
        ]
        baseline = {
            item["trainId"]: item["railVoltage"]
            for item in self.model.constraints_for_positions(positions)
        }

        response = self.model.step(
            1,
            load_payload("P01", ["TR-A"], 1_800_000, 0, 1_200),
            positions,
        )
        constraints = {item["trainId"]: item for item in response["powerConstraints"]}

        self.assertLess(constraints["TR-A"]["railVoltage"], baseline["TR-A"])
        self.assertEqual(constraints["TR-C"]["railVoltage"], baseline["TR-C"])
        self.assertEqual(0.0, constraints["TR-C"]["regenPowerAvailableWatts"])

    def test_regen_absorption_and_unabsorbed_power_are_explicit(self) -> None:
        response = self.model.step(
            1,
            load_payload("P01", ["TR-A", "TR-B"], 600_000, 900_000, 400),
            [
                {"trainId": "TR-A", "positionMeters": 200.0},
                {"trainId": "TR-B", "positionMeters": 700.0},
            ],
        )
        section = next(item for item in response["thirdRailSections"] if item["powerSectionId"] == "P01")

        self.assertEqual(600_000, section["absorbedRegenWatts"])
        self.assertEqual(300_000, section["unabsorbedRegenWatts"])
        self.assertEqual(600_000, section["regenBudgetWatts"])
        self.assertTrue(all(item["regenPowerAvailableWatts"] == 300_000 for item in response["powerConstraints"]))

    def test_regen_without_simultaneous_traction_has_zero_budget(self) -> None:
        response = self.model.step(
            1,
            load_payload("P01", ["TR-A"], 0, 900_000, 0),
            [{"trainId": "TR-A", "positionMeters": 200.0}],
        )
        section = next(item for item in response["thirdRailSections"] if item["powerSectionId"] == "P01")

        self.assertEqual(0, section["absorbedRegenWatts"])
        self.assertEqual(900_000, section["unabsorbedRegenWatts"])
        self.assertEqual(0, response["powerConstraints"][0]["regenPowerAvailableWatts"])
        self.assertFalse(response["powerConstraints"][0]["regenAvailable"])

    def test_duplicate_tick_is_idempotent_and_backward_tick_is_rejected(self) -> None:
        positions = [{"trainId": "TR-A", "positionMeters": 200.0}]
        first = self.model.step(2, load_payload("P01", ["TR-A"], 600_000, 0, 400), positions)
        duplicate = self.model.step(2, load_payload("P01", ["TR-A"], 2_000_000, 0, 1300), positions)

        self.assertEqual(first, duplicate)
        with self.assertRaisesRegex(ValueError, "POWER_TICK_OUT_OF_ORDER"):
            self.model.step(1, load_payload("P01", ["TR-A"], 0, 0, 0), positions)


class PowerStepApiTests(unittest.TestCase):
    def setUp(self) -> None:
        api_model.bootstrap(central_bootstrap_payload())

    def test_http_step_requires_fixed_time_and_maps_backward_tick_to_409(self) -> None:
        payload = {
            "tick": 2,
            "simulationTimeSeconds": 0.2,
            "stepSizeSeconds": 0.1,
            "sectionLoads": [],
            "trainPositions": [{"trainId": "TR-A", "positionMeters": 200.0}],
        }
        first = api_step(TimedPowerStepPayload.model_validate(payload))
        duplicate = api_step(TimedPowerStepPayload.model_validate({**payload, "sectionLoads": [{
                "powerSectionId": "P01",
                "trainIds": ["TR-A"],
                "tractionPowerWatts": 2_000_000,
                "regenPowerWatts": 0,
                "currentAmps": 1300,
            }]}))

        self.assertEqual(first, duplicate)
        with self.assertRaises(HTTPException) as backward:
            api_step(TimedPowerStepPayload.model_validate({**payload, "tick": 1, "simulationTimeSeconds": 0.1}))
        self.assertEqual(409, backward.exception.status_code)
        self.assertIn("POWER_TICK_OUT_OF_ORDER", backward.exception.detail)
        with self.assertRaises(HTTPException) as wrong_step:
            api_step(TimedPowerStepPayload.model_validate({**payload, "tick": 3, "simulationTimeSeconds": 0.6, "stepSizeSeconds": 0.2}))
        self.assertEqual(422, wrong_step.exception.status_code)


def load_payload(
    section_id: str,
    train_ids: list[str],
    traction_power_watts: float,
    regen_power_watts: float,
    current_amps: float,
) -> dict:
    return {
        "sectionLoads": [
            {
                "powerSectionId": section_id,
                "trainIds": train_ids,
                "tractionPowerWatts": traction_power_watts,
                "regenPowerWatts": regen_power_watts,
                "currentAmps": current_amps,
            }
        ]
    }


def two_section_payload() -> dict:
    payload = copy.deepcopy(central_bootstrap_payload())
    payload["topologySegments"].append(
        {
            "id": "SEG-02",
            "rawSegmentId": "RAW-02",
            "startMeters": 1000.0,
            "endMeters": 2000.0,
            "fromNodeId": "N-B",
            "toNodeId": "N-C",
            "track": "UP",
        }
    )
    payload["sectionBindings"].append(
        {
            "powerSectionId": "P02",
            "thirdRailSectionId": "TR-P02",
            "substationId": "SS02",
            "feederId": "F02",
            "startMeters": 1000.0,
            "endMeters": 2000.0,
            "isolatorIds": ["ISO-P02-A"],
        }
    )
    payload["substations"].append(
        {
            "id": "SS02",
            "name": "第二测试牵引所",
            "supplyMode": "DOUBLE_END",
            "sectionIds": ["P02"],
            "devices": [
                {
                    "id": "SS02-RECT",
                    "name": "整流器",
                    "deviceType": "RECTIFIER",
                    "defaultState": "AVAILABLE",
                    "ratedCurrentAmps": 2200.0,
                    "affectsSectionIds": ["P02"],
                },
                {
                    "id": "SS02-DCB",
                    "name": "直流快速断路器",
                    "deviceType": "DC_BREAKER",
                    "defaultState": "CLOSED",
                    "ratedCurrentAmps": 2200.0,
                    "affectsSectionIds": ["P02"],
                },
            ],
        }
    )
    payload["isolators"].append(
        {
            "id": "ISO-P02-A",
            "name": "P02 分区隔离开关",
            "thirdRailSectionId": "TR-P02",
            "positionMeters": 1000.0,
            "defaultState": "CLOSED",
        }
    )
    payload["strayCurrentMonitors"].append(
        {
            "id": "SC-P02",
            "name": "P02 杂散监测点",
            "sectionId": "P02",
            "returnCurrentDeviceId": "RC-P02",
            "positionMeters": 1500.0,
            "normalMinPotentialVolts": -0.8,
            "normalMaxPotentialVolts": 0.8,
        }
    )
    return payload


if __name__ == "__main__":
    unittest.main()
