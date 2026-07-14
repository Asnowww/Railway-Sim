#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
import time
import uuid
from pathlib import Path
from typing import Any

from deployment_http import latency_summary, request_json
from vehicle_acceptance_payload import fleet_request, train_state, update_train_states


ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description="Benchmark 9300+9000+9200 fleet stepping")
    parser.add_argument("--vehicle", default="http://127.0.0.1:9300")
    parser.add_argument("--power", default="http://127.0.0.1:9200")
    parser.add_argument("--samples", type=int, default=100)
    parser.add_argument("--warmup", type=int, default=10)
    parser.add_argument("--endurance-ticks", type=int, default=6000)
    parser.add_argument("--train-counts", default="1,2,10,20")
    parser.add_argument("--endurance-train-count", type=int, default=20)
    parser.add_argument("--output", default=str(ROOT / "docs/真实FMU集成实施计划/验收记录/wp8-performance.json"))
    args = parser.parse_args()
    benchmark_counts = tuple(sorted({int(value) for value in args.train_counts.split(",") if value.strip()}))
    if not benchmark_counts or benchmark_counts[0] <= 0 or args.endurance_train_count <= 0:
        raise SystemExit("train counts must be positive")
    maximum_train_count = max(*benchmark_counts, args.endurance_train_count)
    run_id = f"wp8-performance-{uuid.uuid4()}"

    # Interleave trains across the five real YAML power sections. The endurance
    # benchmark measures normal fleet throughput; same-section overload and
    # voltage-collapse protection are covered by the dedicated WP6 coupling tests.
    section_bases = (500.0, 4500.0, 8000.0, 11500.0, 17000.0)
    all_trains = [
        train_state(
            f"WP8-TR-{index:02d}",
            section_bases[(index - 1) % len(section_bases)] + ((index - 1) // len(section_bases)) * 180.0,
        )
        for index in range(1, maximum_train_count + 1)
    ]
    tick = 0
    errors: list[str] = []
    benchmarks: dict[str, Any] = {}
    _, initial_power_state = request_json(f"{args.power}/power-network/state")
    assert isinstance(initial_power_state, dict)
    initial_accepted_steps = int(initial_power_state.get("acceptedStepCount", 0))

    def run_steps(count: int, samples: int, collect: bool) -> dict[str, Any]:
        nonlocal tick
        wall_latencies: list[float] = []
        runtime_latencies: list[float] = []
        fmu_latencies: list[float] = []
        request_bytes: list[float] = []
        response_bytes: list[float] = []
        degraded = 0
        fallback_ticks = 0
        _, before_health = request_json(f"{args.vehicle}/vehicle-runtime/health")
        assert isinstance(before_health, dict)
        deadline_misses_before = int(before_health.get("missedDeadlineCount", 0))
        deadline_misses_after = deadline_misses_before
        fmi_errors_before = int(before_health.get("fmiErrorCount", 0))
        fmi_errors_after = fmi_errors_before
        trains = all_trains[:count]
        started = time.monotonic()
        for _ in range(samples):
            tick += 1
            step_started = time.perf_counter()
            payload = fleet_request(tick, trains, run_id)
            _, response = request_json(
                f"{args.vehicle}/vehicle-runtime/step-fleet",
                method="POST",
                payload=payload,
                timeout=15.0,
            )
            wall_ms = (time.perf_counter() - step_started) * 1000.0
            assert isinstance(response, dict)
            update_train_states(trains, response)
            _, health = request_json(f"{args.vehicle}/vehicle-runtime/health")
            assert isinstance(health, dict)
            _, power_state = request_json(f"{args.power}/power-network/state")
            assert isinstance(power_state, dict)
            deadline_misses_after = int(health.get("missedDeadlineCount", 0))
            fmi_errors_after = int(health.get("fmiErrorCount", 0))
            if response.get("dataQuality") != "GOOD":
                degraded += 1
            if health.get("simulationRunId") != run_id or health.get("lastAcceptedTick") != tick:
                errors.append(f"tick {tick}: vehicle runtime runId/tick drift")
            if power_state.get("simulationRunId") != run_id or power_state.get("lastAcceptedTick") != tick:
                errors.append(f"tick {tick}: power network runId/tick drift")
            if health.get("fallbackTrainCount") != 0:
                fallback_ticks += 1
            if len(response.get("trainOutputs", [])) != count or len(response.get("trainReports", [])) != count:
                errors.append(f"tick {tick}: expected {count} complete train outputs and reports")
            outputs = response.get("trainOutputs", [])
            expected_ids = {train["id"] for train in trains}
            returned_ids = {output.get("trainId") for output in outputs}
            if returned_ids != expected_ids:
                errors.append(f"tick {tick}: output train set mismatch or instance cross-talk")
            numeric_fields = (
                "newPositionMeters", "newSpeedMetersPerSecond", "accelerationMetersPerSecondSquared",
                "tractionForceNewtons", "brakeForceNewtons", "mechanicalTractionPowerWatts",
                "tractionPowerWatts", "railCurrentAmps", "mechanicalRegenPowerWatts",
                "regenPowerWatts", "energyConsumedKwh", "energyRegeneratedKwh",
            )
            if any(
                not isinstance(output.get(field), (int, float)) or not math.isfinite(float(output[field]))
                for output in outputs
                for field in numeric_fields
            ):
                errors.append(f"tick {tick}: output contains missing, NaN or infinite physical values")
            if any(float(output.get("newSpeedMetersPerSecond", -1)) < 0 for output in outputs):
                errors.append(f"tick {tick}: output contains negative speed")
            if collect:
                wall_latencies.append(wall_ms)
                runtime_latencies.append(float(health.get("latencyMillis", 0)))
                fmu_latencies.append(float(health.get("fmuBatchLatencyMillis", 0)))
                request_bytes.append(float(len(json.dumps(payload, separators=(",", ":")).encode("utf-8"))))
                response_bytes.append(float(len(json.dumps(response, separators=(",", ":")).encode("utf-8"))))
        return {
            "trainCount": count,
            "ticks": samples,
            "simulatedSeconds": samples * 0.1,
            "wallSeconds": round(time.monotonic() - started, 3),
            "httpRoundTrip": latency_summary(wall_latencies),
            "vehicleAndPower": latency_summary(runtime_latencies),
            "fmuBatch": latency_summary(fmu_latencies),
            "requestPayloadBytes": latency_summary(request_bytes),
            "responsePayloadBytes": latency_summary(response_bytes),
            "degradedTicks": degraded,
            "fallbackTicks": fallback_ticks,
            "newFmiErrors": max(0, fmi_errors_after - fmi_errors_before),
            "newMissedDeadlines": max(0, deadline_misses_after - deadline_misses_before),
        }

    for count in benchmark_counts:
        run_steps(count, args.warmup, collect=False)
        result = run_steps(count, args.samples, collect=True)
        benchmarks[str(count)] = result
        if result["fallbackTicks"] != 0 or result["newFmiErrors"] != 0:
            errors.append(f"{count}-train benchmark contains fallback or FMI errors")
        if result["fmuBatch"]["p95Millis"] > 50.0:
            errors.append(f"{count}-train FMU p95 exceeds 50 ms")
        if result["vehicleAndPower"]["p95Millis"] > 80.0:
            errors.append(f"{count}-train vehicle+power p95 exceeds 80 ms")

    endurance = run_steps(args.endurance_train_count, args.endurance_ticks, collect=True)
    if endurance["fallbackTicks"] != 0 or endurance["newFmiErrors"] != 0:
        errors.append(f"{args.endurance_train_count}-train endurance contains fallback or FMI errors")
    if endurance["newMissedDeadlines"] != 0:
        errors.append(f"{args.endurance_train_count}-train endurance missed one or more 100 ms deadlines")
    if endurance["fmuBatch"]["p95Millis"] > 50.0:
        errors.append(f"{args.endurance_train_count}-train endurance FMU p95 exceeds 50 ms")
    if endurance["vehicleAndPower"]["p95Millis"] > 80.0:
        errors.append(f"{args.endurance_train_count}-train endurance vehicle+power p95 exceeds 80 ms")

    _, final_health = request_json(f"{args.vehicle}/vehicle-runtime/health")
    _, final_power_state = request_json(f"{args.power}/power-network/state")
    if final_health.get("fallbackTrainCount") != 0 or final_health.get("fmiErrorCount") != 0:
        errors.append("final runtime health contains fallback or FMI errors")
    expected_steps = tick
    accepted_steps = int(final_power_state.get("acceptedStepCount", 0)) - initial_accepted_steps
    if accepted_steps != expected_steps:
        errors.append(
            f"power network accepted {accepted_steps} writes for {expected_steps} vehicle ticks"
        )

    report = {
        "status": "PASS" if not errors else "FAIL",
        "criteria": {
            "tcmsStepSizeSeconds": 0.1,
            "fmuSubstepSizeSeconds": 0.02,
            "fmuSubstepsPerTcmsTick": 5,
            "fmuBatchP95LimitMillis": 50,
            "vehicleAndPowerP95LimitMillis": 80,
            "benchmarkTrainCounts": benchmark_counts,
            "enduranceTrainCount": args.endurance_train_count,
            "enduranceTicks": args.endurance_ticks,
            "enduranceSimulatedSeconds": args.endurance_ticks * 0.1,
        },
        "errors": errors,
        "simulationRunId": run_id,
        "benchmarks": benchmarks,
        "endurance": endurance,
        "finalHealth": final_health,
        "finalPowerState": final_power_state,
        "powerAcceptedStepDelta": accepted_steps,
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
