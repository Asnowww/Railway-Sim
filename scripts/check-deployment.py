#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json

from deployment_http import request_json


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate Railway-Sim service health and model identity")
    parser.add_argument("--vehicle", default="http://127.0.0.1:9300")
    parser.add_argument("--power", default="http://127.0.0.1:9200")
    parser.add_argument("--fmu", default="http://127.0.0.1:9000")
    parser.add_argument("--allow-java-fallback", action="store_true")
    parser.add_argument("--output")
    args = parser.parse_args()

    _, vehicle = request_json(f"{args.vehicle}/vehicle-runtime/health")
    _, vehicle_parameters = request_json(f"{args.vehicle}/vehicle-runtime/parameters")
    _, power = request_json(f"{args.power}/health")
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
        "vehicleRuntime": vehicle,
        "vehicleParameters": vehicle_parameters,
        "powerNetwork": power,
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
