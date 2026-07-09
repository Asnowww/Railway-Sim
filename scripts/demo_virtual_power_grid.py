#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import sys
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
POWER_SERVICE_ROOT = REPO_ROOT / "power-network-service"
sys.path.insert(0, str(POWER_SERVICE_ROOT))

from app.manager import PowerNetworkModel  # noqa: E402


def build_bootstrap_payload() -> dict[str, Any]:
    return {
        "lineId": "demo-topology-line",
        "lineName": "轨道拓扑支撑虚拟电网演示线",
        "topologySegments": [
            {
                "id": "SEG-DEMO-01",
                "rawSegmentId": 1,
                "startMeters": 0,
                "endMeters": 900,
                "fromNodeId": "N-START",
                "toNodeId": "N-A",
                "track": "UP",
            },
            {
                "id": "SEG-DEMO-02",
                "rawSegmentId": 2,
                "startMeters": 900,
                "endMeters": 1800,
                "fromNodeId": "N-A",
                "toNodeId": "N-MID",
                "track": "UP",
            },
            {
                "id": "SEG-DEMO-03",
                "rawSegmentId": 3,
                "startMeters": 1800,
                "endMeters": 2700,
                "fromNodeId": "N-MID",
                "toNodeId": "N-B",
                "track": "UP",
            },
            {
                "id": "SEG-DEMO-04",
                "rawSegmentId": 4,
                "startMeters": 2700,
                "endMeters": 3600,
                "fromNodeId": "N-B",
                "toNodeId": "N-END",
                "track": "UP",
            },
        ],
        "sectionBindings": [
            {
                "powerSectionId": "P-DEMO-W",
                "thirdRailSectionId": "TRS-DEMO-W",
                "substationId": "SS-DEMO-W",
                "feederId": "F-DEMO-W",
                "startMeters": 0,
                "endMeters": 1800,
                "isolatorIds": ["ISO-DEMO-W-A", "ISO-DEMO-W-B"],
            },
            {
                "powerSectionId": "P-DEMO-E",
                "thirdRailSectionId": "TRS-DEMO-E",
                "substationId": "SS-DEMO-E",
                "feederId": "F-DEMO-E",
                "startMeters": 1800,
                "endMeters": 3600,
                "isolatorIds": ["ISO-DEMO-E-A", "ISO-DEMO-E-B"],
            },
        ],
        "substations": [
            {
                "id": "SS-DEMO-W",
                "name": "西端牵引变电所",
                "supplyMode": "DOUBLE_END",
                "startMeters": 0,
                "endMeters": 1800,
                "sectionIds": ["P-DEMO-W"],
                "devices": [
                    device("SS-DEMO-W-TR", "西端牵引变压器", "TRACTION_TRANSFORMER", "P-DEMO-W"),
                    device("SS-DEMO-W-REC", "西端整流器", "RECTIFIER", "P-DEMO-W"),
                    device("SS-DEMO-W-DCB", "西端直流快速断路器", "DC_BREAKER", "P-DEMO-W", "CLOSED"),
                ],
            },
            {
                "id": "SS-DEMO-E",
                "name": "东端牵引变电所",
                "supplyMode": "DOUBLE_END",
                "startMeters": 1800,
                "endMeters": 3600,
                "sectionIds": ["P-DEMO-E"],
                "devices": [
                    device("SS-DEMO-E-TR", "东端牵引变压器", "TRACTION_TRANSFORMER", "P-DEMO-E"),
                    device("SS-DEMO-E-REC", "东端整流器", "RECTIFIER", "P-DEMO-E"),
                    device("SS-DEMO-E-DCB", "东端直流快速断路器", "DC_BREAKER", "P-DEMO-E", "CLOSED"),
                ],
            },
        ],
        "isolators": [
            isolator("ISO-DEMO-W-A", "西端首端隔离开关", "TRS-DEMO-W", 0),
            isolator("ISO-DEMO-W-B", "西端末端隔离开关", "TRS-DEMO-W", 1800),
            isolator("ISO-DEMO-E-A", "东端首端隔离开关", "TRS-DEMO-E", 1800),
            isolator("ISO-DEMO-E-B", "东端末端隔离开关", "TRS-DEMO-E", 3600),
        ],
        "strayCurrentMonitors": [
            stray_monitor("SCP-DEMO-W", "西段极化电位监测点", "P-DEMO-W", 900),
            stray_monitor("SCP-DEMO-E", "东段极化电位监测点", "P-DEMO-E", 2700),
        ],
    }


def device(
    device_id: str,
    name: str,
    device_type: str,
    section_id: str,
    default_state: str = "AVAILABLE",
) -> dict[str, Any]:
    return {
        "id": device_id,
        "name": name,
        "deviceType": device_type,
        "defaultState": default_state,
        "ratedVoltage": 750,
        "ratedCurrentAmps": 2200,
        "affectsSectionIds": [section_id],
    }


def isolator(isolator_id: str, name: str, third_rail_section_id: str, position_meters: float) -> dict[str, Any]:
    return {
        "id": isolator_id,
        "name": name,
        "thirdRailSectionId": third_rail_section_id,
        "positionMeters": position_meters,
        "defaultState": "CLOSED",
    }


def stray_monitor(point_id: str, name: str, section_id: str, position_meters: float) -> dict[str, Any]:
    return {
        "id": point_id,
        "name": name,
        "sectionId": section_id,
        "returnCurrentDeviceId": f"RCD-{section_id}",
        "positionMeters": position_meters,
        "normalMinPotentialVolts": -0.8,
        "normalMaxPotentialVolts": 1.8,
    }


def section_by_id(snapshot: dict[str, Any], section_id: str) -> dict[str, Any]:
    return next(section for section in snapshot["thirdRailSections"] if section["powerSectionId"] == section_id)


def main() -> None:
    model = PowerNetworkModel()
    bootstrap = build_bootstrap_payload()
    model.bootstrap(bootstrap)
    topology = model.topology()
    loaded = model.query_state(
        {
            "sectionLoads": [
                {
                    "powerSectionId": "P-DEMO-W",
                    "tractionPowerWatts": 1_050_000,
                    "regenPowerWatts": 0,
                    "currentAmps": 1_350,
                },
                {
                    "powerSectionId": "P-DEMO-E",
                    "tractionPowerWatts": 650_000,
                    "regenPowerWatts": 120_000,
                    "currentAmps": 900,
                },
            ]
        }
    )

    print("=== Virtual Power Grid Demo ===")
    print(f"line: {topology['lineId']} / {topology['lineName']}")
    print(
        "track topology: "
        f"{len(topology['topologySegments'])} segments, "
        f"{len({node for segment in topology['topologySegments'] for node in (segment['fromNodeId'], segment['toNodeId'])})} nodes, "
        "0-3600m"
    )
    for segment in topology["topologySegments"]:
        print(
            "  track "
            f"{segment['id']}: {segment['fromNodeId']} -> {segment['toNodeId']}, "
            f"{segment['startMeters']:.0f}-{segment['endMeters']:.0f}m"
        )

    print("\nvirtual power objects:")
    print(f"  medium-voltage ring: {len(topology['mediumVoltageBuses'])} buses, {len(topology['ringFeeders'])} feeders")
    print(f"  substations: {', '.join(substation['id'] for substation in topology['substations'])}")
    print(f"  third rail sections: {', '.join(section['powerSectionId'] for section in topology['thirdRailSections'])}")

    print("\nload response:")
    for section_id in ("P-DEMO-W", "P-DEMO-E"):
        section = section_by_id(loaded, section_id)
        print(
            f"  {section_id}: {section['energizationState']}, "
            f"{section['recommendedSupplyMode']}, "
            f"{section['contactRailVoltage']:.1f}V, "
            f"{section['tractionCurrentAmps']:.0f}A"
        )

    result = model.operate(
        {
            "targetType": "SUBSTATION_DEVICE",
            "targetId": "SS-DEMO-W-DCB",
            "desiredState": "TRIPPED",
            "operationType": "BREAKER_TRIP",
            "reason": "demo breaker trip",
            "traceId": "demo-virtual-grid",
        }
    )
    after_trip = model.snapshot()
    west_after_trip = section_by_id(after_trip, "P-DEMO-W")
    west_risk = next(point for point in after_trip["strayCurrentMonitors"] if point["sectionId"] == "P-DEMO-W")

    print("\nfault operation:")
    print(f"  trip SS-DEMO-W-DCB: executed={result['executed']}, result={result['resultState']}")
    print(
        "  P-DEMO-W after trip: "
        f"{west_after_trip['energizationState']}, "
        f"{west_after_trip['recommendedSupplyMode']}, "
        f"reason={west_after_trip['supportReason']}"
    )
    print(f"  stray current risk: {west_risk['riskLevel']} ({west_risk['riskReason']})")


if __name__ == "__main__":
    main()
