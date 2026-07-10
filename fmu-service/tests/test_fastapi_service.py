from __future__ import annotations

import copy
import json
from pathlib import Path

from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator

from app.main import app


ROOT = Path(__file__).resolve().parents[1]
PROJECT_ROOT = ROOT.parent


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def init_payload(train_id: str = "API-TRAIN") -> dict:
    payload = load_json(ROOT / "contracts" / "examples" / "step-fleet-request.example.json")
    payload["tick"] = 1
    payload["simulationTimeSeconds"] = 0.0
    payload["traceId"] = "api-init-1"
    train = payload["trains"][0]
    train["trainId"] = train_id
    train["lifecycleCommand"] = "INIT"
    train["positionMeters"] = 0.0
    train["speedMetersPerSecond"] = 10.0
    train["previousEnergyConsumedKwh"] = 0.0
    train["previousEnergyRegeneratedKwh"] = 0.0
    return payload


def test_fastapi_health_metadata_validation_and_step_contract() -> None:
    response_schema = load_json(ROOT / "contracts" / "step-fleet-response.schema.json")
    with TestClient(app) as client:
        health = client.get("/health")
        assert health.status_code == 200
        assert health.json()["status"] == "UP"
        assert health.json()["ready"] is True

        metadata = client.get("/fmu/metadata")
        assert metadata.status_code == 200
        assert metadata.json()["fmpyVersion"] == "0.3.30"
        assert metadata.json()["variableValidation"]["status"] == "VALID"

        validation = client.post("/fmu/validate")
        assert validation.status_code == 200
        assert validation.json()["status"] == "VALID"

        response = client.post("/step-fleet", json=init_payload())
        assert response.status_code == 200
        Draft202012Validator(response_schema).validate(response.json())
        assert response.json()["trainOutputs"][0]["instanceState"] == "ACTIVE"
        assert response.json()["trainErrors"] == []


def test_fastapi_status_codes_and_management_endpoints() -> None:
    with TestClient(app) as client:
        missing = init_payload("MISSING-FIELD")
        del missing["trains"][0]["trainMassKg"]
        response = client.post("/step-fleet", json=missing)
        assert response.status_code == 422
        assert response.json()["errorCode"] == "INVALID_REQUEST"

        invalid_step = init_payload("BAD-STEP")
        invalid_step["stepSizeSeconds"] = 0.2
        response = client.post("/step-fleet", json=invalid_step)
        assert response.status_code == 400
        assert response.json()["errorCode"] == "INVALID_STEP_SIZE"
        assert response.json()["traceId"] == invalid_step["traceId"]

        invalid_type = init_payload("BAD-TYPE")
        invalid_type["trains"][0]["trainMassKg"] = "236000"
        response = client.post("/step-fleet", json=invalid_type)
        assert response.status_code == 422
        assert response.json()["errorCode"] == "INVALID_REQUEST"
        assert response.json()["traceId"] == invalid_type["traceId"]

        init = init_payload("MANAGED")
        response = client.post("/step-fleet", json=init)
        assert response.status_code == 200

        reset = copy.deepcopy(init)
        reset["tick"] = 2
        reset["simulationTimeSeconds"] = 5.0
        reset["traceId"] = "managed-reset-2"
        reset["trains"][0]["lifecycleCommand"] = "RESET"
        reset["trains"][0]["positionMeters"] = 500.0
        response = client.post("/instances/MANAGED/reset", json=reset)
        assert response.status_code == 200
        assert response.json()["trainOutputs"][0]["newPositionMeters"] >= 500.0

        deletion = client.delete("/instances/MANAGED")
        assert deletion.status_code == 200
        assert deletion.json()["instanceState"] == "TERMINATED"

        init_a = init_payload("RESET-A")
        init_a["trains"].append(copy.deepcopy(init_a["trains"][0]))
        init_a["trains"][1]["trainId"] = "RESET-B"
        init_a["tick"] = 3
        init_a["traceId"] = "reset-all-init"
        response = client.post("/step-fleet", json=init_a)
        assert response.status_code == 200
        reset_all = client.post("/instances/reset-all")
        assert reset_all.status_code == 200
        assert reset_all.json()["resetInstanceCount"] == 2


def test_fastapi_unknown_step_and_conflicting_tick_return_409() -> None:
    with TestClient(app) as client:
        unknown = init_payload("UNKNOWN")
        unknown["trains"][0]["lifecycleCommand"] = "STEP"
        response = client.post("/step-fleet", json=unknown)
        assert response.status_code == 409
        assert response.json()["errorCode"] == "FMU_INSTANCE_NOT_FOUND"

        initial = init_payload("CONFLICT")
        assert client.post("/step-fleet", json=initial).status_code == 200
        conflicting = copy.deepcopy(initial)
        conflicting["traceId"] = "different-trace"
        response = client.post("/step-fleet", json=conflicting)
        assert response.status_code == 409
        assert response.json()["errorCode"] == "FMU_TICK_CONFLICT"
