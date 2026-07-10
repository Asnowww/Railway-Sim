from __future__ import annotations

from threading import RLock
from typing import Annotated, Any

from fastapi import FastAPI
from pydantic import BaseModel, Field

from .manager import PowerNetworkModel


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
model = PowerNetworkModel()
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
def health() -> dict[str, str]:
    return {"status": "UP", "role": "AUTHORITATIVE_POWER_SIMULATOR"}


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
def step(payload: PowerStepPayload) -> dict[str, Any]:
    """Apply one fleet-load snapshot and return the next control-cycle power constraints."""
    with model_lock:
        snapshot = model.query_state(as_load_request(payload))
        return {**snapshot, "powerConstraints": model.constraints_for_positions(as_positions(payload))}
