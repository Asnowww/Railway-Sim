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

    central = PowerNetworkModel()
    central.bootstrap(central_bootstrap_payload())
    no_load = central.snapshot()["thirdRailSections"][0]
    assert no_load["contactRailVoltage"] == 1500.0
    traction = central.query_state(
        {
            "sectionLoads": [
                {
                    "powerSectionId": "P01",
                    "tractionPowerWatts": 1_800_000,
                    "regenPowerWatts": 0,
                    "currentAmps": 1_200,
                }
            ]
        }
    )
    traction_section = traction["thirdRailSections"][0]
    assert 1450.0 < traction_section["contactRailVoltage"] < 1500.0
    regen = central.query_state(
        {
            "sectionLoads": [
                {
                    "powerSectionId": "P01",
                    "tractionPowerWatts": 1_800_000,
                    "regenPowerWatts": 900_000,
                    "currentAmps": 1_200,
                }
            ]
        }
    )
    assert regen["thirdRailSections"][0]["contactRailVoltage"] > traction_section["contactRailVoltage"]
    central.operate(
        {
            "targetType": "STRAY_MONITOR",
            "targetId": "SC-P01",
            "desiredState": "ABNORMAL",
            "operationType": "STRAY_CABINET_FAULT",
            "reason": "self-test",
        }
    )
    high_current = central.query_state(
        {
            "sectionLoads": [
                {
                    "powerSectionId": "P01",
                    "tractionPowerWatts": 3_000_000,
                    "regenPowerWatts": 0,
                    "currentAmps": 2_100,
                }
            ]
        }
    )
    assert high_current["strayCurrentMonitors"][0]["riskLevel"] == "CRITICAL"
    print("power-network-service self-test passed")


def central_bootstrap_payload() -> dict:
    return {
        "lineId": "central-test",
        "lineName": "中央额定电压测试线",
        "nominalVoltage": 1500.0,
        "minimumVoltage": 1100.0,
        "cutoffVoltage": 900.0,
        "maxTractionCurrentAmps": 2200.0,
        "topologySegments": [
            {
                "id": "SEG-01",
                "rawSegmentId": "RAW-01",
                "startMeters": 0.0,
                "endMeters": 1000.0,
                "fromNodeId": "N-A",
                "toNodeId": "N-B",
                "track": "UP",
            }
        ],
        "sectionBindings": [
            {
                "powerSectionId": "P01",
                "thirdRailSectionId": "TR-P01",
                "substationId": "SS01",
                "feederId": "F01",
                "startMeters": 0.0,
                "endMeters": 1000.0,
                "isolatorIds": ["ISO-P01-A"],
            }
        ],
        "substations": [
            {
                "id": "SS01",
                "name": "测试牵引所",
                "supplyMode": "DOUBLE_END",
                "startMeters": 0.0,
                "endMeters": 1000.0,
                "sectionIds": ["P01"],
                "devices": [
                    {
                        "id": "SS01-RECT",
                        "name": "整流器",
                        "deviceType": "RECTIFIER",
                        "defaultState": "AVAILABLE",
                        "ratedVoltage": 1500.0,
                        "ratedCurrentAmps": 2200.0,
                        "affectsSectionIds": ["P01"],
                    },
                    {
                        "id": "SS01-DCB",
                        "name": "直流快速断路器",
                        "deviceType": "DC_BREAKER",
                        "defaultState": "CLOSED",
                        "ratedVoltage": 1500.0,
                        "ratedCurrentAmps": 2200.0,
                        "affectsSectionIds": ["P01"],
                    },
                ],
            }
        ],
        "isolators": [
            {
                "id": "ISO-P01-A",
                "name": "P01 分区隔离开关",
                "thirdRailSectionId": "TR-P01",
                "positionMeters": 0.0,
                "defaultState": "CLOSED",
            }
        ],
        "strayCurrentMonitors": [
            {
                "id": "SC-P01",
                "name": "P01 杂散监测点",
                "sectionId": "P01",
                "returnCurrentDeviceId": "RC-P01",
                "positionMeters": 500.0,
                "normalMinPotentialVolts": -0.8,
                "normalMaxPotentialVolts": 0.8,
            }
        ],
    }


if __name__ == "__main__":
    main()
