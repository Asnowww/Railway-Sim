from __future__ import annotations

import copy
import json
import msgpack
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
        paths = client.get("/openapi.json").json()["paths"]
        assert "/instances/{trainId}" in paths
        assert "/instances/{trainId}/reset" in paths

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


def test_messagepack_step_contract_matches_json_contract() -> None:
    payload = init_payload("MSGPACK-TRAIN")
    with TestClient(app) as client:
        response = client.post(
            "/step-fleet-msgpack",
            content=msgpack.packb(payload, use_bin_type=True),
            headers={"Content-Type": "application/msgpack", "Accept": "application/msgpack"},
        )

        assert response.status_code == 200
        assert response.headers["content-type"].startswith("application/msgpack")
        decoded = msgpack.unpackb(response.content, raw=False)
        assert decoded["tick"] == payload["tick"]
        assert decoded["traceId"] == payload["traceId"]
        assert decoded["trainOutputs"][0]["trainId"] == "MSGPACK-TRAIN"
        assert decoded["trainErrors"] == []


def test_compact_messagepack_substeps_reuse_static_input_and_return_full_final_output() -> None:
    payload = init_payload("COMPACT-TRAIN")
    with TestClient(app) as client:
        initial = client.post(
            "/step-fleet-msgpack",
            content=msgpack.packb(payload, use_bin_type=True),
            headers={"Content-Type": "application/msgpack", "Accept": "application/msgpack"},
        )
        assert initial.status_code == 200
        initial_output = msgpack.unpackb(initial.content, raw=False)["trainOutputs"][0]

        compact_payload = {
            "t": 2,
            "s": 0.02,
            "d": 0.02,
            "m": payload["modelVersion"],
            "p": payload["parameterSetId"],
            "r": "compact-step-2",
            "f": False,
            "u": [[
                "COMPACT-TRAIN",
                initial_output["newPositionMeters"],
                initial_output["newSpeedMetersPerSecond"],
                initial_output["energyConsumedKwh"],
                initial_output["energyRegeneratedKwh"],
            ]],
        }
        compact = client.post(
            "/step-fleet-compact",
            content=msgpack.packb(compact_payload, use_bin_type=True),
            headers={"Content-Type": "application/msgpack", "Accept": "application/msgpack"},
        )
        assert compact.status_code == 200
        compact_decoded = msgpack.unpackb(compact.content, raw=False)
        assert compact_decoded[:4] == [
            2,
            payload["modelVersion"],
            payload["parameterSetId"],
            "compact-step-2",
        ]
        compact_output = compact_decoded[4][0]
        assert len(compact_output) == 5
        assert compact_output[0] == "COMPACT-TRAIN"

        compact_payload["t"] = 3
        compact_payload["s"] = 0.04
        compact_payload["r"] = "compact-step-3"
        compact_payload["f"] = True
        compact_payload["u"][0][1:] = [
            compact_output[1],
            compact_output[2],
            compact_output[3],
            compact_output[4],
        ]
        final = client.post(
            "/step-fleet-compact",
            content=msgpack.packb(compact_payload, use_bin_type=True),
            headers={"Content-Type": "application/msgpack", "Accept": "application/msgpack"},
        )
        assert final.status_code == 200
        final_output = msgpack.unpackb(final.content, raw=False)["trainOutputs"][0]
        assert "tractionPowerWatts" in final_output
        assert final_output["trainId"] == "COMPACT-TRAIN"


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
