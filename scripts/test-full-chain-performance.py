#!/usr/bin/env python3
"""Benchmark one complete 8080 -> 9300 -> 9000/9200 simulation tick.

The benchmark deliberately drives 8080 while PAUSED, so every measured tick is
caused by exactly one POST /api/simulation/tick and cannot race the scheduler.
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any
from urllib.request import Request, urlopen

from deployment_http import latency_summary, request_json
from vehicle_acceptance_payload import train_state


ROOT = Path(__file__).resolve().parents[1]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Benchmark the full split-mode vehicle/power tick")
    parser.add_argument("--backend", default="http://127.0.0.1:18080")
    parser.add_argument("--vehicle", default="http://127.0.0.1:19300")
    parser.add_argument("--power", default="http://127.0.0.1:19200")
    parser.add_argument("--train-count", type=int, required=True)
    parser.add_argument("--warmup-ticks", type=int, default=5)
    parser.add_argument("--sample-ticks", type=int, default=30)
    parser.add_argument("--label", required=True)
    parser.add_argument("--output", required=True)
    return parser.parse_args()


def measured_post(url: str, timeout: float = 60.0) -> tuple[float, int, dict[str, Any]]:
    request = Request(url, data=b"", method="POST", headers={"Accept": "application/json"})
    started = time.perf_counter()
    with urlopen(request, timeout=timeout) as response:
        content = response.read()
    wall_millis = (time.perf_counter() - started) * 1000.0
    return wall_millis, len(content), json.loads(content)


def position_for(index: int) -> float:
    # Evenly exercise all five power sections without putting a train outside
    # the configured 0..47,504 m authoritative topology.
    anchors = (500.0, 4_500.0, 8_000.0, 12_000.0, 17_000.0)
    return anchors[index % len(anchors)] + (index // len(anchors)) * 2.0


def setup_fleet(args: argparse.Namespace) -> None:
    request_json(f"{args.backend}/api/simulation/reset", method="POST", timeout=30.0)
    request_json(f"{args.backend}/api/simulation/pause", method="POST", timeout=30.0)
    _, central_trains = request_json(f"{args.backend}/api/trains", timeout=30.0)
    initial_ids = [str(item["id"]) for item in central_trains]
    if len(initial_ids) > args.train_count:
        raise RuntimeError(
            f"requested {args.train_count} trains but central reset creates {len(initial_ids)}"
        )

    train_ids = list(initial_ids)
    for index in range(len(train_ids), args.train_count):
        train_id = f"PERF-{index + 1:04d}"
        position = position_for(index)
        _, launch = request_json(
            f"{args.vehicle}/vehicle-runtime/trains/launch",
            method="POST",
            payload={
                "trainId": train_id,
                "linkId": 1,
                "offsetMeters": position,
                "direction": "DOWN",
                "registerWithCentral": True,
                "reason": "FULL_CHAIN_PERFORMANCE",
                "traceId": f"full-chain-{args.label}-{train_id}",
            },
            timeout=30.0,
        )
        if launch.get("centralRegistrationStatus") != "REGISTERED":
            raise RuntimeError(f"central registration failed for {train_id}: {launch}")
        train_ids.append(train_id)

    # Launch creates the runtime instance; this authoritative registration also
    # sets an explicit, distributed position for the power-section assignment.
    for index, train_id in enumerate(train_ids):
        request_json(
            f"{args.vehicle}/vehicle-runtime/trains/{train_id}",
            method="PUT",
            payload=train_state(train_id, position_for(index)),
            timeout=30.0,
        )

    _, final_central = request_json(f"{args.backend}/api/trains", timeout=30.0)
    _, final_runtime = request_json(f"{args.vehicle}/vehicle-runtime/instances", timeout=30.0)
    if len(final_central) != args.train_count or len(final_runtime) != args.train_count:
        raise RuntimeError(
            f"fleet setup mismatch: central={len(final_central)}, runtime={len(final_runtime)}, "
            f"expected={args.train_count}"
        )


def stage_summary(samples: list[dict[str, Any]], group: str, field: str) -> dict[str, float | int]:
    return latency_summary([float(sample[group][field]) for sample in samples])


def main() -> None:
    args = parse_args()
    if args.train_count < 2 or args.warmup_ticks < 1 or args.sample_ticks < 1:
        raise SystemExit("train-count must be >= 2 and warmup/sample ticks must be positive")

    setup_fleet(args)
    _, power_before = request_json(f"{args.power}/power-network/state", timeout=30.0)
    accepted_before = int(power_before.get("acceptedStepCount", 0))
    samples: list[dict[str, Any]] = []
    tick_evidence: list[dict[str, Any]] = []
    errors: list[str] = []
    total_ticks = args.warmup_ticks + args.sample_ticks

    for sample_index in range(total_ticks):
        wall_millis, response_bytes, snapshot = measured_post(
            f"{args.backend}/api/simulation/tick"
        )
        _, central_timing = request_json(f"{args.backend}/api/simulation/timing")
        _, runtime_timing = request_json(f"{args.vehicle}/vehicle-runtime/timing")
        _, runtime_health = request_json(f"{args.vehicle}/vehicle-runtime/health")
        _, power_state = request_json(f"{args.power}/power-network/state", timeout=30.0)

        tick = int(snapshot.get("tick", -1))
        if len(snapshot.get("trains", [])) != args.train_count:
            errors.append(f"tick {tick}: central train count mismatch")
        if int(central_timing.get("trainCount", -1)) != args.train_count:
            errors.append(f"tick {tick}: central timing train count mismatch")
        if int(runtime_timing.get("trainCount", -1)) != args.train_count:
            errors.append(f"tick {tick}: runtime timing train count mismatch")
        # Dense capacity fleets intentionally overlap on a finite demonstration
        # line and may trigger ATP/current-collection protection.  That produces
        # PARTIAL_STEP/INVALID domain data without indicating a communication or
        # FMU failure, so the performance gate records it but only rejects actual
        # fallback/forwarding failures.
        if runtime_health.get("reason") in {
            "PHYSICS_FALLBACK_ACTIVE",
            "POWER_LOAD_FORWARD_FAILED",
        }:
            errors.append(f"tick {tick}: runtime reason={runtime_health.get('reason')}")
        if int(runtime_health.get("fallbackTrainCount", 0)) != 0:
            errors.append(f"tick {tick}: fallbackTrainCount={runtime_health.get('fallbackTrainCount')}")
        if int(runtime_health.get("fmiErrorCount", 0)) != 0:
            errors.append(f"tick {tick}: fmiErrorCount={runtime_health.get('fmiErrorCount')}")
        if int(power_state.get("lastAcceptedTick", -1)) != tick:
            errors.append(
                f"tick {tick}: 9200 lastAcceptedTick={power_state.get('lastAcceptedTick')}"
            )

        tick_evidence.append({
            "tick": tick,
            "runtimeTiming": runtime_timing,
            "runtimeDataQuality": runtime_health.get("dataQuality"),
            "runtimeReason": runtime_health.get("reason"),
            "powerLastAcceptedTick": power_state.get("lastAcceptedTick"),
            "powerAcceptedStepCount": power_state.get("acceptedStepCount"),
        })

        if sample_index >= args.warmup_ticks:
            samples.append(
                {
                    "tick": tick,
                    "httpWallMillis": round(wall_millis, 3),
                    "responseBytes": response_bytes,
                    "central": central_timing,
                    "runtime": runtime_timing,
                    "runtimeHealth": {
                        "latencyMillis": runtime_health.get("latencyMillis"),
                        "fmuBatchLatencyMillis": runtime_health.get("fmuBatchLatencyMillis"),
                        "fallbackTrainCount": runtime_health.get("fallbackTrainCount"),
                        "fmiErrorCount": runtime_health.get("fmiErrorCount"),
                        "dataQuality": runtime_health.get("dataQuality"),
                        "reason": runtime_health.get("reason"),
                    },
                }
            )

    _, power_after = request_json(f"{args.power}/power-network/state", timeout=30.0)
    accepted_delta = int(power_after.get("acceptedStepCount", 0)) - accepted_before
    sequential_writes = 0
    for index, evidence in enumerate(tick_evidence):
        if int(evidence["powerLastAcceptedTick"]) != int(evidence["tick"]):
            continue
        if index == 0:
            sequential_writes += 1
            continue
        previous = tick_evidence[index - 1]
        if int(evidence["powerAcceptedStepCount"]) - int(previous["powerAcceptedStepCount"]) == 1:
            sequential_writes += 1
        else:
            errors.append(
                f"tick {evidence['tick']}: 9200 acceptedStepCount did not increase by exactly one"
            )
    if sequential_writes != total_ticks:
        errors.append(
            f"9200 sequential accepted writes={sequential_writes}, expected exactly {total_ticks}"
        )

    report = {
        "label": args.label,
        "status": "PASS" if not errors else "FAIL",
        "criteria": {
            "trainCount": args.train_count,
            "warmupTicks": args.warmup_ticks,
            "sampleTicks": args.sample_ticks,
            "tcmsPeriodMillis": 100,
            "fmuSubstepMillis": 20,
            "fmuSubstepsPerTick": 5,
            "singlePowerLoadWriter": "9300",
        },
        "errors": sorted(set(errors)),
        "singleWriterEvidence": {
            "acceptedStepCountBefore": accepted_before,
            "acceptedStepCountAfter": int(power_after.get("acceptedStepCount", 0)),
            "acceptedStepCountDelta": accepted_delta,
            "sequentialAcceptedWrites": sequential_writes,
            "expectedSequentialWrites": total_ticks,
        },
        "tickEvidence": tick_evidence,
        "metrics": {
            "httpWall": latency_summary([sample["httpWallMillis"] for sample in samples]),
            "centralTotal": stage_summary(samples, "central", "totalMillis"),
            "centralConstraintPreparation": stage_summary(
                samples, "central", "constraintPreparationMillis"
            ),
            "centralTrainStateAndTrackConstraint": stage_summary(
                samples, "central", "trainStateAndTrackConstraintMillis"
            ),
            "centralPreliminarySignalAndDispatch": stage_summary(
                samples, "central", "preliminarySignalAndDispatchMillis"
            ),
            "centralCommandAndInterlocking": stage_summary(
                samples, "central", "commandAndInterlockingMillis"
            ),
            "centralFinalConstraintAndPowerBootstrap": stage_summary(
                samples, "central", "finalConstraintAndPowerBootstrapMillis"
            ),
            "centralVehicleRuntime": stage_summary(samples, "central", "vehicleRuntimeMillis"),
            "centralPowerSnapshot": stage_summary(
                samples, "central", "authoritativePowerSnapshotMillis"
            ),
            "centralServiceHealth": stage_summary(samples, "central", "serviceHealthMillis"),
            "centralAlarmProjection": stage_summary(samples, "central", "alarmProjectionMillis"),
            "centralAlarmReconciliation": stage_summary(
                samples, "central", "alarmReconciliationMillis"
            ),
            "centralSnapshotBuild": stage_summary(samples, "central", "snapshotBuildMillis"),
            "centralWebSocketPush": stage_summary(samples, "central", "webSocketPushMillis"),
            "runtimeTotal": stage_summary(samples, "runtime", "totalMillis"),
            "runtimePowerConstraintQuery": stage_summary(
                samples, "runtime", "powerConstraintQueryMillis"
            ),
            "runtimeControlPreparation": stage_summary(
                samples, "runtime", "controlPreparationMillis"
            ),
            "runtimeFmuFleetStep": stage_summary(samples, "runtime", "fmuFleetStepMillis"),
            "runtimeStateApply": stage_summary(samples, "runtime", "stateApplyMillis"),
            "runtimePowerNetworkStep": stage_summary(
                samples, "runtime", "powerNetworkStepMillis"
            ),
            "responseBytes": latency_summary([float(sample["responseBytes"]) for sample in samples]),
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
