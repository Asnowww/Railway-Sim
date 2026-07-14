#!/usr/bin/env python3
"""Reproducible 9300 -> FMU shard scale acceptance for large fleets."""

from __future__ import annotations

import argparse
import json
import math
import statistics
import time
import uuid
from pathlib import Path
from typing import Any

from deployment_http import request_json
from vehicle_acceptance_payload import fleet_request, train_state, update_train_states


ROOT = Path(__file__).resolve().parents[1]


def percentile(values: list[float], quantile: float) -> float:
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(quantile * len(ordered)) - 1))
    return ordered[index]


def summary(values: list[float]) -> dict[str, float]:
    return {
        "mean": round(statistics.mean(values), 3),
        "median": round(statistics.median(values), 3),
        "p95": round(percentile(values, 0.95), 3),
        "min": round(min(values), 3),
        "max": round(max(values), 3),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Test 1000-train 9300/FMU communication scale")
    parser.add_argument("--vehicle", default="http://127.0.0.1:9300")
    parser.add_argument("--train-count", type=int, default=1000)
    parser.add_argument("--warmup-ticks", type=int, default=2)
    parser.add_argument("--sample-ticks", type=int, default=100)
    parser.add_argument("--runtime-p95-limit-ms", type=float, default=80.0)
    parser.add_argument("--fmu-p95-limit-ms", type=float, default=60.0)
    parser.add_argument(
        "--output",
        default=str(
            ROOT
            / "docs/真实FMU集成实施计划/验收记录/wp8-1000-train-scale.json"
        ),
    )
    args = parser.parse_args()
    if args.train_count <= 0 or args.warmup_ticks < 1 or args.sample_ticks < 1:
        raise SystemExit("train-count and sample-ticks must be positive; warmup-ticks must be >= 1")

    run_id = f"wp8-{args.train_count}-scale-{uuid.uuid4()}"
    trains = [
        train_state(
            f"WP8-SCALE-{index:04d}",
            500.0 + (index % 10) * 1000.0 + (index // 10) * 2.0,
        )
        for index in range(args.train_count)
    ]
    errors: list[str] = []
    samples: list[dict[str, Any]] = []
    total_ticks = args.warmup_ticks + args.sample_ticks

    for tick in range(1, total_ticks + 1):
        payload = fleet_request(tick, trains, run_id)
        request_bytes = len(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
        started = time.perf_counter()
        _, response = request_json(
            f"{args.vehicle}/vehicle-runtime/step-fleet",
            method="POST",
            payload=payload,
            timeout=30.0,
        )
        wall_millis = (time.perf_counter() - started) * 1000.0
        if not isinstance(response, dict):
            raise AssertionError("vehicle runtime response must be a JSON object")
        update_train_states(trains, response)
        _, health = request_json(f"{args.vehicle}/vehicle-runtime/health")
        if not isinstance(health, dict):
            raise AssertionError("vehicle runtime health must be a JSON object")

        outputs = response.get("trainOutputs", [])
        returned_ids = {output.get("trainId") for output in outputs}
        expected_ids = {train["id"] for train in trains}
        if len(outputs) != args.train_count or returned_ids != expected_ids:
            errors.append(f"tick {tick}: output train set mismatch")
        if response.get("dataQuality") != "GOOD":
            errors.append(f"tick {tick}: dataQuality={response.get('dataQuality')}")
        if health.get("fallbackTrainCount") != 0:
            errors.append(f"tick {tick}: fallbackTrainCount={health.get('fallbackTrainCount')}")
        if health.get("fmiErrorCount") != 0:
            errors.append(f"tick {tick}: fmiErrorCount={health.get('fmiErrorCount')}")
        if any(float(output.get("newSpeedMetersPerSecond", -1.0)) < 0.0 for output in outputs):
            errors.append(f"tick {tick}: negative train speed")

        if tick > args.warmup_ticks:
            samples.append(
                {
                    "tick": tick,
                    "wallMillis": round(wall_millis, 3),
                    "runtimeMillis": float(health.get("latencyMillis", 0.0)),
                    "fmuBatchMillis": float(health.get("fmuBatchLatencyMillis", 0.0)),
                    "requestPayloadBytes": request_bytes,
                    "responsePayloadBytes": len(
                        json.dumps(response, separators=(",", ":")).encode("utf-8")
                    ),
                }
            )

    runtime = summary([sample["runtimeMillis"] for sample in samples])
    fmu = summary([sample["fmuBatchMillis"] for sample in samples])
    if runtime["p95"] > args.runtime_p95_limit_ms:
        errors.append(
            f"runtime p95 {runtime['p95']} ms exceeds {args.runtime_p95_limit_ms} ms"
        )
    if fmu["p95"] > args.fmu_p95_limit_ms:
        errors.append(f"FMU p95 {fmu['p95']} ms exceeds {args.fmu_p95_limit_ms} ms")

    report = {
        "status": "PASS" if not errors else "FAIL",
        "simulationRunId": run_id,
        "criteria": {
            "trainCount": args.train_count,
            "fmuPeriodMillis": 20,
            "tcmsPeriodMillis": 100,
            "fmuSubstepsPerTcmsTick": 5,
            "warmupTicks": args.warmup_ticks,
            "sampleTicks": args.sample_ticks,
            "runtimeP95LimitMillis": args.runtime_p95_limit_ms,
            "fmuP95LimitMillis": args.fmu_p95_limit_ms,
        },
        "errors": sorted(set(errors)),
        "metrics": {
            "httpRoundTripMillis": summary([sample["wallMillis"] for sample in samples]),
            "vehicleRuntimeMillis": runtime,
            "fmuBatchMillis": fmu,
            "requestPayloadBytes": summary(
                [float(sample["requestPayloadBytes"]) for sample in samples]
            ),
            "responsePayloadBytes": summary(
                [float(sample["responsePayloadBytes"]) for sample in samples]
            ),
        },
        "samples": samples,
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(rendered + "\n", encoding="utf-8")
    if errors:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
