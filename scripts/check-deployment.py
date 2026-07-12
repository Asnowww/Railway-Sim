#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

from deployment_http import request_json


def current_commit() -> str:
    root = Path(__file__).resolve().parents[1]
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=root, text=True
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return "UNKNOWN"


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate Railway-Sim service health and model identity")
    parser.add_argument("--vehicle", default="http://127.0.0.1:9300")
    parser.add_argument("--power", default="http://127.0.0.1:9200")
    parser.add_argument("--fmu", default="http://127.0.0.1:9000")
    parser.add_argument("--backend", default="http://127.0.0.1:8080")
    parser.add_argument("--allow-java-fallback", action="store_true")
    parser.add_argument("--output")
    args = parser.parse_args()

    _, vehicle = request_json(f"{args.vehicle}/vehicle-runtime/health")
    _, vehicle_parameters = request_json(f"{args.vehicle}/vehicle-runtime/parameters")
    _, power = request_json(f"{args.power}/health")
    _, power_state = request_json(f"{args.power}/power-network/state")
    _, snapshot = request_json(f"{args.backend}/api/simulation/snapshot")
    _, service_health = request_json(f"{args.backend}/api/service-health")
    fmu = None
    metadata = None
    validation = None
    if not args.allow_java_fallback:
        _, fmu = request_json(f"{args.fmu}/health")
        _, metadata = request_json(f"{args.fmu}/fmu/metadata")
        _, validation = request_json(f"{args.fmu}/fmu/validate", method="POST")

    errors: list[str] = []
    if vehicle.get("heartbeatStatus") != "UP":
        errors.append("vehicle runtime is not UP")
    if power.get("status") != "UP":
        errors.append("power network is not UP")
    if power.get("nominalVoltage") != 1500.0:
        errors.append("power network did not load the 1500 V project configuration")
    if power.get("powerSectionCount") != 5:
        errors.append("power network did not load the five project power sections")
    if not isinstance(snapshot, dict) or not snapshot.get("simulationRunId"):
        errors.append("central snapshot has no simulationRunId")
    health_by_id = {
        item.get("serviceId"): item
        for item in service_health if isinstance(item, dict)
    } if isinstance(service_health, list) else {}
    for service_id in ("vehicle-runtime-9300", "power-network-9200"):
        service = health_by_id.get(service_id)
        if service is None:
            errors.append(f"central service health missing {service_id}")
            continue
        if service.get("state") != "UP":
            errors.append(f"central service health {service_id} is {service.get('state')}")
        if not service.get("configHash") or not service.get("modelVersion") or not service.get("parameterVersion"):
            errors.append(f"central service health {service_id} lacks config/model/parameter identity")
    if not vehicle.get("configHash") or not vehicle.get("stoppingParameterVersion"):
        errors.append("vehicle runtime lacks configHash or stoppingParameterVersion")
    for field in ("topologyHash", "configHash", "modelVersion", "parameterVersion"):
        if not power_state.get(field):
            errors.append(f"power network state lacks {field}")
    central_run_id = snapshot.get("simulationRunId") if isinstance(snapshot, dict) else None
    for payload_name, payload in (("vehicle", vehicle), ("power", power_state)):
        service_run_id = payload.get("simulationRunId")
        if service_run_id and central_run_id and service_run_id != central_run_id:
            errors.append(f"{payload_name} runId {service_run_id} != central {central_run_id}")
    if args.allow_java_fallback:
        if vehicle.get("physicsMode") != "JAVA_FALLBACK":
            errors.append("vehicle runtime is not in JAVA_FALLBACK mode")
    else:
        if vehicle.get("physicsMode") != "FMU_HTTP":
            errors.append("vehicle runtime is not in FMU_HTTP mode")
        if fmu is None or fmu.get("status") != "UP" or not fmu.get("ready"):
            errors.append("FMU service is not ready")
        if metadata is None or vehicle.get("parameterSetId") != metadata.get("parameterSetId"):
            errors.append("9300/9000 parameterSetId mismatch")
        if metadata is None or vehicle_parameters.get("curveSetId") != metadata.get("curveSetId"):
            errors.append("9300/9000 curveSetId mismatch")
        if metadata is None or vehicle.get("fmuModelVersion") != metadata.get("modelVersion"):
            errors.append("9300/9000 modelVersion mismatch")
        if metadata is None or metadata.get("parameterSchemaVersion") != "2":
            errors.append("FMU parameter schema is not v2")
        if metadata is None or metadata.get("curvePointCount") != 52:
            errors.append("FMU did not load the 52-point motor curves")
        if metadata is None or metadata.get("lengthMeters") != 118.0:
            errors.append("FMU vehicle length is not 118 m")
        if vehicle_parameters.get("lengthMeters") != 118.0:
            errors.append("vehicle runtime vehicle length is not 118 m")
        validation_status = (validation or {}).get("variableValidation", {}).get("status")
        if validation_status not in {"VALID", None}:
            errors.append(f"FMU variable validation is {validation_status}")

    report = {
        "status": "PASS" if not errors else "FAIL",
        "errors": errors,
        "commit": current_commit(),
        "centralSnapshot": snapshot,
        "centralServiceHealth": service_health,
        "vehicleRuntime": vehicle,
        "vehicleParameters": vehicle_parameters,
        "powerNetwork": power,
        "powerNetworkState": power_state,
        "fmu": fmu,
        "fmuMetadata": metadata,
        "fmuValidation": validation,
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as file:
            file.write(rendered + "\n")
    if errors:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
