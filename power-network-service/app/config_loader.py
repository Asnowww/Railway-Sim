from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any

import yaml


def resolve_power_config_path(configured_path: str | None = None) -> Path:
    """Resolve the explicit override or the repository/container default config."""
    if configured_path and configured_path.strip():
        return Path(configured_path.strip())
    return Path(__file__).resolve().parents[2] / "config" / "power_third_rail.yaml"


def load_power_config(path: str | Path) -> tuple[dict[str, Any], str]:
    source = Path(path)
    raw = source.read_bytes()
    document = yaml.safe_load(raw)
    if not isinstance(document, dict):
        raise ValueError(f"power config must be a YAML object: {source}")

    sections = document.get("sections") or []
    substations = document.get("substations") or []
    third_rails = document.get("thirdRailSections") or []
    isolators = document.get("isolators") or []
    monitors = document.get("strayCurrentMonitorPoints") or []
    if not sections or not substations or not third_rails:
        raise ValueError(f"power config requires sections, substations and thirdRailSections: {source}")

    third_rail_by_section = {item["powerSectionId"]: item for item in third_rails}
    bindings: list[dict[str, Any]] = []
    for section in sections:
        third_rail = third_rail_by_section.get(section["id"])
        if third_rail is None:
            raise ValueError(f"missing thirdRailSection for power section {section['id']}")
        bindings.append(
            {
                "powerSectionId": section["id"],
                "thirdRailSectionId": third_rail["id"],
                "substationId": section["substationId"],
                "feederId": section["feederId"],
                "startMeters": float(section["startMeters"]),
                "endMeters": float(section["endMeters"]),
                "isolatorIds": list(third_rail.get("isolatorIds") or []),
            }
        )

    payload = {
        "lineId": document.get("lineId", "yaml-power-network"),
        "lineName": document.get("lineName", "YAML供电网络"),
        "nominalVoltage": float(document["nominalVoltage"]),
        "minimumVoltage": float(document["minimumVoltage"]),
        "cutoffVoltage": float(document["cutoffVoltage"]),
        "maxTractionCurrentAmps": float(document["maxTractionCurrentAmps"]),
        "topologySegments": list(document.get("topologySegments") or []),
        "sectionBindings": bindings,
        "substations": substations,
        "isolators": isolators,
        "strayCurrentMonitors": monitors,
    }
    return payload, "sha256:" + hashlib.sha256(raw).hexdigest()
