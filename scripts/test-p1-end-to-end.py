#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path
from typing import Any
from urllib.parse import urlencode

from deployment_http import request_json, wait_json


ROOT = Path(__file__).resolve().parents[1]


def compose(env_file: str, *args: str) -> None:
    subprocess.run(
        ["docker", "compose", "--env-file", env_file, *args],
        cwd=ROOT,
        check=True,
    )


def require(condition: bool, message: str, errors: list[str]) -> None:
    if not condition:
        errors.append(message)


def tick(backend: str, expected_status: int = 200) -> dict[str, Any]:
    _, payload = request_json(
        f"{backend}/api/simulation/tick",
        method="POST",
        expected_status=expected_status,
        timeout=20.0,
    )
    return payload if isinstance(payload, dict) else {}


def service_health(backend: str) -> dict[str, dict[str, Any]]:
    _, payload = request_json(f"{backend}/api/service-health")
    return {
        item.get("serviceId", ""): item
        for item in payload
        if isinstance(item, dict)
    } if isinstance(payload, list) else {}


def train_summary(snapshot: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {
        train["id"]: {
            "positionMeters": train.get("positionMeters"),
            "speedMetersPerSecond": train.get("speedMetersPerSecond"),
            "dynamicsState": train.get("dynamicsState"),
            "currentCollectionStatus": train.get("currentCollectionStatus"),
            "faultCode": train.get("faultCode"),
        }
        for train in snapshot.get("trains", [])
        if isinstance(train, dict) and train.get("id")
    }


def power_section(snapshot: dict[str, Any], section_id: str) -> dict[str, Any]:
    return next(
        (
            section
            for section in snapshot.get("powerSections", [])
            if isinstance(section, dict) and section.get("id") == section_id
        ),
        {},
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="P1 split-chain fault visibility and 9300/9200 restart acceptance"
    )
    parser.add_argument("--env-file", default=str(ROOT / "deploy/server.env"))
    parser.add_argument("--backend", default="http://127.0.0.1:8080")
    parser.add_argument("--vehicle", default="http://127.0.0.1:9300")
    parser.add_argument("--power", default="http://127.0.0.1:9200")
    parser.add_argument("--section", default="P01")
    parser.add_argument("--output")
    args = parser.parse_args()

    errors: list[str] = []
    evidence: dict[str, Any] = {}
    section_id = args.section

    baseline = tick(args.backend)
    run_id = baseline.get("simulationRunId")
    require(bool(run_id), "baseline snapshot has no simulationRunId", errors)
    evidence["baseline"] = {
        "runId": run_id,
        "tick": baseline.get("tick"),
        "trains": train_summary(baseline),
    }

    fault_request = {
        "faultType": "DEENERGIZED",
        "reason": "P1 end-to-end acceptance",
        "operator": "p1-acceptance",
        "confirmToken": "SIMULATION_CONFIRM",
        "traceId": "p1-e2e-power-fault",
    }
    fault_started = time.monotonic()
    request_json(
        f"{args.backend}/api/power/sections/{section_id}/faults",
        method="POST",
        payload=fault_request,
    )
    fault_snapshot = tick(args.backend)
    query = urlencode({"runId": run_id})
    _, alarm_payload = request_json(f"{args.backend}/api/alarms?{query}")
    fault_visible_seconds = time.monotonic() - fault_started
    section = power_section(fault_snapshot, section_id)
    affected_train_ids = set(section.get("affectedTrainIds", []))
    trains = train_summary(fault_snapshot)
    relevant_alarms = [
        alarm for alarm in alarm_payload
        if isinstance(alarm, dict) and alarm.get("locationRef") == section_id
    ] if isinstance(alarm_payload, list) else []
    require(fault_visible_seconds <= 5.0, "power fault was not visible within 5 seconds", errors)
    require(section.get("status") == "DEENERGIZED", "central power state did not deenergize", errors)
    require(float(section.get("voltage", -1)) == 0.0, "central power mirror did not use authoritative zero voltage", errors)
    require(bool(affected_train_ids), "faulted section has no affected trains", errors)
    for train_id in affected_train_ids:
        train = trains.get(train_id, {})
        require(train.get("currentCollectionStatus") == "LOST", f"{train_id} did not lose current collection", errors)
        require(train.get("dynamicsState") in {"POWER_LOSS", "SAFETY_BRAKE"}, f"{train_id} did not enter a safe dynamics state", errors)
    require(bool(relevant_alarms), "power fault generated no alarm", errors)
    require(any(
        affected_train_ids.issubset(set((alarm.get("impact") or {}).get("affectedTrainIds", [])))
        for alarm in relevant_alarms
    ), "power alarm impact does not include affected trains", errors)
    evidence["powerFault"] = {
        "visibleSeconds": round(fault_visible_seconds, 3),
        "section": section,
        "trains": trains,
        "alarms": relevant_alarms,
    }

    request_json(
        f"{args.backend}/api/power/sections/{section_id}/faults/clear",
        method="POST",
        payload={**fault_request, "reason": "P1 end-to-end acceptance clear", "traceId": "p1-e2e-power-clear"},
    )
    cleared = tick(args.backend)
    require(power_section(cleared, section_id).get("status") == "ENERGIZED", "power fault did not clear", errors)
    before_vehicle_outage = train_summary(cleared)

    compose(args.env_file, "stop", "vehicle-runtime")
    tick(args.backend, expected_status=500)
    outage_health = service_health(args.backend).get("vehicle-runtime-9300", {})
    require(outage_health.get("state") == "FALLBACK", "9300 outage did not enter FALLBACK", errors)
    compose(args.env_file, "start", "vehicle-runtime")
    fresh_vehicle = wait_json(
        f"{args.vehicle}/vehicle-runtime/health",
        lambda value: value.get("heartbeatStatus") == "UP",
        240.0,
    )
    require(fresh_vehicle.get("bootstrapped") is False, "fresh 9300 did not expose bootstrapped=false", errors)
    require(fresh_vehicle.get("instanceCount") == 0, "fresh 9300 unexpectedly retained instances", errors)
    vehicle_recovered = tick(args.backend)
    recovered_health = service_health(args.backend).get("vehicle-runtime-9300", {})
    recovered_trains = train_summary(vehicle_recovered)
    require(recovered_health.get("state") == "UP", "9300 did not recover to UP", errors)
    require((recovered_health.get("recoveryGate") or {}).get("accepted") is True, "9300 recovery gate was not accepted", errors)
    for train_id, before in before_vehicle_outage.items():
        after = recovered_trains.get(train_id, {})
        require(train_id in recovered_trains, f"{train_id} missing after 9300 restart", errors)
        delta = float(after.get("positionMeters", 0)) - float(before.get("positionMeters", 0))
        require(0 <= delta <= 5.0, f"{train_id} position discontinuity after 9300 restart: {delta}", errors)
    evidence["vehicleRuntimeRecovery"] = {
        "outageHealth": outage_health,
        "freshHealth": fresh_vehicle,
        "recoveredHealth": recovered_health,
        "beforeTrains": before_vehicle_outage,
        "recoveredTrains": recovered_trains,
    }

    compose(args.env_file, "stop", "power-network")
    tick(args.backend, expected_status=500)
    power_outage_health = service_health(args.backend).get("power-network-9200", {})
    require(power_outage_health.get("state") == "FALLBACK", "9200 outage did not enter FALLBACK", errors)
    compose(args.env_file, "start", "power-network")
    wait_json(f"{args.power}/health", lambda value: value.get("status") == "UP", 240.0)
    _, prebootstrap = request_json(
        f"{args.power}/power-network/constraints/query",
        method="POST",
        payload={"trainPositions": []},
        expected_status=409,
    )
    require(prebootstrap.get("detail") == "POWER_BOOTSTRAP_REQUIRED", "9200 did not reject pre-bootstrap query", errors)
    power_recovered = tick(args.backend)
    power_health = service_health(args.backend).get("power-network-9200", {})
    _, final_power = request_json(f"{args.power}/power-network/state")
    require(power_health.get("state") == "UP", "9200 did not recover to UP", errors)
    require((power_health.get("recoveryGate") or {}).get("accepted") is True, "9200 recovery gate was not accepted", errors)
    require(final_power.get("simulationRunId") == run_id, "9200 runId drifted after restart", errors)
    require(final_power.get("lastAcceptedTick") == power_recovered.get("tick"), "9200 tick watermark drifted after restart", errors)
    evidence["powerNetworkRecovery"] = {
        "outageHealth": power_outage_health,
        "prebootstrapResponse": prebootstrap,
        "recoveredHealth": power_health,
        "finalPowerState": final_power,
    }

    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    report = {
        "status": "PASS" if not errors else "FAIL",
        "commit": commit,
        "simulationRunId": run_id,
        "criteria": {
            "faultVisibleSecondsMax": 5,
            "vehicleRecoveryPositionJumpMetersMax": 5,
            "recoveryGateRequired": True,
            "oldTickOverwriteAllowed": False,
        },
        "errors": errors,
        "evidence": evidence,
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered + "\n", encoding="utf-8")
    if errors:
        raise SystemExit(1)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"P1 end-to-end acceptance failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        raise
