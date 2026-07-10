from __future__ import annotations

import os
from threading import RLock
from typing import Annotated, Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .manager import PowerNetworkModel
from .config_loader import load_power_config


class SectionLoadPayload(BaseModel):
    power_section_id: str = Field(alias="powerSectionId")
    train_ids: list[str] = Field(default_factory=list, alias="trainIds")
    traction_power_watts: float = Field(default=0.0, alias="tractionPowerWatts")
    regen_power_watts: float = Field(default=0.0, alias="regenPowerWatts")
    current_amps: float = Field(default=0.0, alias="currentAmps")


class TrainPositionPayload(BaseModel):
    train_id: str = Field(alias="trainId")
    position_meters: float = Field(alias="positionMeters")


class PowerConstraintQueryPayload(BaseModel):
    train_positions: list[TrainPositionPayload] = Field(default_factory=list, alias="trainPositions")


class PowerStepPayload(PowerConstraintQueryPayload):
    section_loads: list[SectionLoadPayload] = Field(default_factory=list, alias="sectionLoads")


class TimedPowerStepPayload(PowerStepPayload):
    tick: int
    simulation_time_seconds: float = Field(alias="simulationTimeSeconds")
    step_size_seconds: float = Field(alias="stepSizeSeconds")


class PowerOperationPayload(BaseModel):
    target_type: str = Field(alias="targetType")
    target_id: str = Field(alias="targetId")
    desired_state: str | None = Field(default=None, alias="desiredState")
    operation_type: str | None = Field(default=None, alias="operationType")
    reason: str | None = None
    trace_id: str | None = Field(default=None, alias="traceId")


app = FastAPI(
    title="Railway-Sim Power Network Simulator",
    version="1.0.0",
    description="供电仿真权威服务：计算分区负荷、电压、电流与车辆供电约束。",
)
power_config_source = os.environ.get("POWER_NETWORK_CONFIG_PATH", "").strip()
power_config_sha256 = ""
model = PowerNetworkModel()
if power_config_source:
    bootstrap_payload, power_config_sha256 = load_power_config(power_config_source)
    model.bootstrap(bootstrap_payload)
model_lock = RLock()


def as_load_request(payload: PowerStepPayload) -> dict[str, Any]:
    return {"sectionLoads": [item.model_dump(by_alias=True) for item in payload.section_loads]}


def as_positions(payload: PowerConstraintQueryPayload) -> list[dict[str, Any]]:
    return [item.model_dump(by_alias=True) for item in payload.train_positions]


@app.get("/")
def root() -> dict[str, str]:
    return {
        "service": "railway-sim-power-network",
        "role": "authoritative-power-simulator",
        "docs": "/docs",
    }


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "UP",
        "role": "AUTHORITATIVE_POWER_SIMULATOR",
        "configSource": power_config_source or "BUILT_IN_REFERENCE",
        "configSha256": power_config_sha256,
        "nominalVoltage": model.nominal_dc_voltage,
        "powerSectionCount": len(model.third_rail_sections),
    }


@app.get("/power-network/state")
def state() -> dict[str, Any]:
    with model_lock:
        return model.snapshot()


@app.get("/power-network/events")
def events() -> list[dict[str, Any]]:
    with model_lock:
        return model.event_list()


@app.get("/power-network/topology")
def topology() -> dict[str, Any]:
    with model_lock:
        return model.topology()


@app.post("/power-network/bootstrap")
def bootstrap(payload: dict[str, Any]) -> dict[str, Any]:
    with model_lock:
        return model.bootstrap(payload)


@app.post("/power-network/operations")
def operate(payload: PowerOperationPayload) -> dict[str, Any]:
    with model_lock:
        return model.operate(payload.model_dump(by_alias=True, exclude_none=True))


@app.post("/power-network/state/query")
def query_state(payload: PowerStepPayload) -> dict[str, Any]:
    with model_lock:
        return model.query_state(as_load_request(payload))


@app.post("/power-network/constraints/query")
def query_constraints(payload: PowerConstraintQueryPayload) -> dict[str, Any]:
    with model_lock:
        snapshot = model.snapshot()
        return {**snapshot, "powerConstraints": model.constraints_for_positions(as_positions(payload))}


@app.post("/power-network/step")
def step(payload: TimedPowerStepPayload) -> dict[str, Any]:
    """Apply one fleet-load snapshot and return the next control-cycle power constraints."""
    if abs(payload.step_size_seconds - 0.1) > 1.0e-9:
        raise HTTPException(status_code=422, detail="stepSizeSeconds must equal 0.1")
    if payload.tick < 0 or abs(payload.simulation_time_seconds - payload.tick * payload.step_size_seconds) > 1.0e-9:
        raise HTTPException(status_code=422, detail="simulationTimeSeconds must equal tick * stepSizeSeconds")
    with model_lock:
        try:
            return model.step(payload.tick, as_load_request(payload), as_positions(payload))
        except ValueError as exception:
            raise HTTPException(status_code=409, detail=str(exception)) from exception
