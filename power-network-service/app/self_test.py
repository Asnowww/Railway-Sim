from __future__ import annotations

from .manager import PowerNetworkModel


def main() -> None:
    model = PowerNetworkModel()
    initial = model.query_state(
        {
            "sectionLoads": [
                {
                    "powerSectionId": "P-CJG-W",
                    "tractionPowerWatts": 1_200_000,
                    "regenPowerWatts": 0,
                    "currentAmps": 1_600,
                }
            ]
        }
    )
    assert initial["thirdRailSections"][0]["contactRailVoltage"] > 700
    result = model.operate(
        {
            "targetType": "SUBSTATION_DEVICE",
            "targetId": "SS-CJG-W-DCB",
            "desiredState": "TRIPPED",
            "operationType": "BREAKER_TRIP",
            "reason": "self-test",
        }
    )
    assert result["executed"] is True
    tripped = model.snapshot()
    target = next(section for section in tripped["thirdRailSections"] if section["powerSectionId"] == "P-CJG-W")
    assert target["recommendedSupplyMode"] in {"SINGLE_END", "CROSS_FEED", "OUTAGE"}
    risk = next(point for point in tripped["strayCurrentMonitors"] if point["sectionId"] == "P-CJG-W")
    assert risk["riskLevel"] in {"NORMAL", "ATTENTION", "WARNING", "CRITICAL"}
    print("power-network-service self-test passed")


if __name__ == "__main__":
    main()
