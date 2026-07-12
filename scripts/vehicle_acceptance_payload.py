from __future__ import annotations

from datetime import datetime, timezone
from typing import Any


def train_state(train_id: str, position: float, speed: float = 0.0) -> dict[str, Any]:
    return {
        "id": train_id,
        "routeId": "demo-line",
        "serviceNo": train_id,
        "controlSessionState": "IN_SERVICE",
        "signalNetworkStatus": "ATTACHED",
        "powerNetworkStatus": "ATTACHED",
        "controlSessionReason": "EXTERNAL_RUNTIME",
        "linkId": 1,
        "direction": "DOWN",
        "positionMeters": position,
        "speedMetersPerSecond": speed,
        "lengthMeters": 118.0,
        "headMileage": position,
        "tailMileage": max(0.0, position - 118.0),
        "loadRate": 0.42,
        "loadMassKg": 0.0,
        "overloadStatus": "NORMAL",
        "availableTractionCount": 4,
        "availableBrakeCount": 4,
        "vehicleProtectionReason": "NONE",
        "status": "RUNNING",
        "operationMode": "ATO",
        "zeroSpeed": speed <= 0.05,
        "doorState": "CLOSED_LOCKED",
        "tractionState": "IDLE",
        "brakeState": "RELEASED",
        "currentCollectionStatus": "NORMAL",
        "tractionAvailable": True,
        "brakeAvailable": True,
        "selfCheckStatus": "PASS",
        "faultLevel": 0,
        "availableOperationMode": "NORMAL",
        "dataQuality": "GOOD",
        "dynamicsState": "COASTING",
        "dynamicsConstraintReason": "INITIAL",
        "speedLimitMetersPerSecond": 22.2,
        "vehicleFaultSpeedLimitMetersPerSecond": 0.0,
        "movementAuthorityDistanceMeters": 60_000.0 - position,
        "stationDistanceMeters": 1_000_000.0,
        "stoppingDistanceMeters": 0.0,
        "accelerationMetersPerSecondSquared": 0.0,
        "tractionForceNewtons": 0.0,
        "brakeForceNewtons": 0.0,
        "regenBrakeForceNewtons": 0.0,
        "railCurrentAmps": 0.0,
        "tractionPowerWatts": 0.0,
        "regenPowerWatts": 0.0,
        "energyConsumedKwh": 0.0,
        "energyRegeneratedKwh": 0.0,
        "faultCode": "OK",
    }


def fleet_request(
    tick: int,
    trains: list[dict[str, Any]],
    simulation_run_id: str = "acceptance-run",
) -> dict[str, Any]:
    authorities = [
        {"trainId": train["id"], "authorityEndMeters": 60_000.0, "speedLimitMetersPerSecond": 22.2, "reason": "NORMAL"}
        for train in trains
    ]
    tracks = [
        {
            "trainId": train["id"],
            "segmentId": "SEG-1",
            "speedLimitMetersPerSecond": 22.2,
            "gradient": 0.0,
            "curveRadiusMeters": 1000.0,
            "stationDistanceMeters": 1_000_000.0,
        }
        for train in trains
    ]
    return {
        "tick": tick,
        "deltaSeconds": 0.1,
        "requestedAt": datetime.now(timezone.utc).isoformat(),
        "trains": trains,
        "movementAuthorities": authorities,
        "trackConstraints": tracks,
        "dispatchConstraints": [],
        "powerConstraints": [],
        "simulationRunId": simulation_run_id,
        "driverCommands": [],
    }


def update_train_states(trains: list[dict[str, Any]], response: dict[str, Any]) -> None:
    outputs = {item["trainId"]: item for item in response.get("trainOutputs", [])}
    for train in trains:
        output = outputs.get(train["id"])
        if output is None:
            continue
        position = float(output["newPositionMeters"])
        speed = float(output["newSpeedMetersPerSecond"])
        train["positionMeters"] = position
        train["headMileage"] = position
        train["tailMileage"] = max(0.0, position - float(train["lengthMeters"]))
        train["speedMetersPerSecond"] = speed
        train["zeroSpeed"] = speed <= 0.05
        train["accelerationMetersPerSecondSquared"] = float(output["accelerationMetersPerSecondSquared"])
        train["tractionForceNewtons"] = float(output["tractionForceNewtons"])
        train["brakeForceNewtons"] = float(output["brakeForceNewtons"])
        train["regenBrakeForceNewtons"] = float(output["regenBrakeForceNewtons"])
        train["railCurrentAmps"] = float(output["railCurrentAmps"])
        train["tractionPowerWatts"] = float(output["tractionPowerWatts"])
        train["regenPowerWatts"] = float(output["regenPowerWatts"])
        train["energyConsumedKwh"] = float(output["energyConsumedKwh"])
        train["energyRegeneratedKwh"] = float(output["energyRegeneratedKwh"])
        train["faultCode"] = output["faultCode"]
