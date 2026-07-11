#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

from deployment_http import request_json, wait_json
from vehicle_acceptance_payload import fleet_request, train_state, update_train_states


ROOT = Path(__file__).resolve().parents[1]


def compose(env_file: str, *args: str) -> None:
    subprocess.run(
        ["docker", "compose", "--env-file", env_file, *args],
        cwd=ROOT,
        check=True,
    )


def step(vehicle_url: str, tick: int, trains: list[dict[str, Any]]) -> dict[str, Any]:
    _, response = request_json(
        f"{vehicle_url}/vehicle-runtime/step-fleet",
        method="POST",
        payload=fleet_request(tick, trains),
        timeout=15.0,
    )
    assert isinstance(response, dict)
    update_train_states(trains, response)
    return response


def health(vehicle_url: str) -> dict[str, Any]:
    _, payload = request_json(f"{vehicle_url}/vehicle-runtime/health")
    assert isinstance(payload, dict)
    return payload


def require(condition: bool, message: str, errors: list[str]) -> None:
    if not condition:
        errors.append(message)


def evidence_summary(response: dict[str, Any], runtime_health: dict[str, Any]) -> dict[str, Any]:
    return {
        "tick": response.get("tick"),
        "dataQuality": response.get("dataQuality"),
        "trainFaultCodes": {
            output.get("trainId"): output.get("faultCode")
            for output in response.get("trainOutputs", [])
        },
        "fallbackTrainCount": runtime_health.get("fallbackTrainCount"),
        "fallbackEventCount": runtime_health.get("fallbackEventCount"),
        "fmiErrorCount": runtime_health.get("fmiErrorCount"),
        "runtimeReason": runtime_health.get("reason"),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Exercise FMU outage, instance loss and explicit RESYNC")
    parser.add_argument("--env-file", default=str(ROOT / "deploy/server.env"))
    parser.add_argument("--vehicle", default="http://127.0.0.1:9300")
    parser.add_argument("--fmu", default="http://127.0.0.1:9000")
    parser.add_argument("--output")
    args = parser.parse_args()

    errors: list[str] = []
    evidence: dict[str, Any] = {}
    trains = [train_state("WP7-TR-01", 100.0), train_state("WP7-TR-02", 220.0)]

    initial = step(args.vehicle, 1, trains)
    initial_health = health(args.vehicle)
    require(initial.get("dataQuality") == "GOOD", "INIT did not return GOOD", errors)
    require(initial_health.get("fallbackTrainCount") == 0, "INIT unexpectedly used fallback", errors)
    evidence["initial"] = evidence_summary(initial, initial_health)

    request_json(f"{args.fmu}/instances/WP7-TR-02", method="DELETE")
    instance_loss = step(args.vehicle, 2, trains)
    instance_loss_health = health(args.vehicle)
    require(instance_loss.get("dataQuality") == "DEGRADED", "instance loss did not degrade the fleet step", errors)
    require(instance_loss_health.get("fallbackTrainCount", 0) >= 1, "instance loss did not enter sticky fallback", errors)
    evidence["instanceLoss"] = evidence_summary(instance_loss, instance_loss_health)

    request_json(f"{args.vehicle}/vehicle-runtime/physics/instances/resync-all", method="POST")
    instance_resync = step(args.vehicle, 3, trains)
    instance_resync_health = health(args.vehicle)
    require(instance_resync.get("dataQuality") == "GOOD", "RESYNC after instance loss did not recover", errors)
    require(instance_resync_health.get("fallbackTrainCount") == 0, "RESYNC left trains in fallback", errors)
    evidence["instanceResync"] = evidence_summary(instance_resync, instance_resync_health)

    compose(args.env_file, "stop", "fmu")
    outage = step(args.vehicle, 4, trains)
    outage_health = health(args.vehicle)
    require(outage.get("dataQuality") == "DEGRADED", "FMU outage did not degrade the fleet step", errors)
    require(outage_health.get("fallbackTrainCount") == len(trains), "FMU outage did not put the fleet in fallback", errors)
    evidence["fmuOutage"] = evidence_summary(outage, outage_health)

    compose(args.env_file, "start", "fmu")
    wait_json(f"{args.fmu}/health", lambda value: value.get("status") == "UP" and value.get("ready"), 240.0)
    sticky = step(args.vehicle, 5, trains)
    sticky_health = health(args.vehicle)
    require(sticky.get("dataQuality") == "DEGRADED", "fallback recovered without explicit RESYNC", errors)
    require(sticky_health.get("fallbackTrainCount") == len(trains), "sticky fallback was not retained", errors)
    evidence["stickyFallback"] = evidence_summary(sticky, sticky_health)

    request_json(f"{args.vehicle}/vehicle-runtime/physics/instances/resync-all", method="POST")
    recovered = step(args.vehicle, 6, trains)
    recovered_health = health(args.vehicle)
    require(recovered.get("dataQuality") == "GOOD", "explicit RESYNC after service restart did not recover", errors)
    require(recovered_health.get("fallbackTrainCount") == 0, "fallback remained after service restart RESYNC", errors)
    evidence["serviceRecovery"] = evidence_summary(recovered, recovered_health)

    _, metadata = request_json(f"{args.fmu}/fmu/metadata")
    bad_request = {
        "tick": 999999,
        "simulationTimeSeconds": 99999.9,
        "stepSizeSeconds": 0.1,
        "modelVersion": metadata["modelVersion"],
        "parameterSetId": "sha256:intentional-mismatch",
        "traceId": "wp7-parameter-mismatch",
        "trains": [],
    }
    _, mismatch = request_json(
        f"{args.fmu}/step-fleet",
        method="POST",
        payload=bad_request,
        expected_status=409,
    )
    require(mismatch.get("errorCode") == "FMU_PARAMETER_SET_MISMATCH", "parameter mismatch error code is incorrect", errors)
    evidence["parameterMismatch"] = mismatch

    report = {"status": "PASS" if not errors else "FAIL", "errors": errors, "evidence": evidence}
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        Path(args.output).parent.mkdir(parents=True, exist_ok=True)
        Path(args.output).write_text(rendered + "\n", encoding="utf-8")
    if errors:
        raise SystemExit(1)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"WP7 recovery acceptance failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        raise
