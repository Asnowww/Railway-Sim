from __future__ import annotations

from contextlib import asynccontextmanager
import json
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .api_models import StepFleetRequestPayload
from .fmu_manager import FmuManager, FmuProtocolError
from .schemas import step_fleet_response_to_dict


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.manager = None
    app.state.startup_error = None
    try:
        app.state.manager = FmuManager()
    except Exception as exc:
        app.state.startup_error = f"{type(exc).__name__}: {exc}"
    try:
        yield
    finally:
        manager = app.state.manager
        if manager is not None:
            manager.close()


app = FastAPI(
    title="Railway-Sim Vehicle FMU Service",
    version="1.0.0",
    lifespan=lifespan,
)


def _error_payload(
    status_code: int,
    error_code: str,
    message: str,
    trace_id: str = "",
) -> dict[str, Any]:
    return {
        "status": status_code,
        "errorCode": error_code,
        "message": message,
        "traceId": trace_id,
    }


def _manager(request: Request) -> FmuManager:
    manager = getattr(request.app.state, "manager", None)
    if manager is None:
        startup_error = getattr(request.app.state, "startup_error", None)
        raise FmuProtocolError(
            503,
            "FMU_SERVICE_NOT_READY",
            startup_error or "FMU service is not ready",
        )
    return manager


@app.exception_handler(FmuProtocolError)
async def fmu_protocol_error_handler(
    request: Request,
    exc: FmuProtocolError,
) -> JSONResponse:
    trace_id = exc.trace_id or request.headers.get("X-Trace-Id", "")
    return JSONResponse(
        status_code=exc.status_code,
        content=_error_payload(
            exc.status_code,
            exc.error_code,
            str(exc),
            trace_id,
        ),
    )


@app.exception_handler(RequestValidationError)
async def request_validation_error_handler(
    request: Request,
    exc: RequestValidationError,
) -> JSONResponse:
    body = exc.body if isinstance(exc.body, dict) else {}
    trace_id = str(body.get("traceId", request.headers.get("X-Trace-Id", "")))
    errors = exc.errors()
    if errors:
        first = errors[0]
        location = ".".join(str(part) for part in first.get("loc", ()))
        message = f"{location}: {first.get('msg', 'invalid request')}"
    else:
        message = "request validation failed"
    return JSONResponse(
        status_code=422,
        content=_error_payload(422, "INVALID_REQUEST", message, trace_id),
    )


@app.exception_handler(Exception)
async def unclassified_error_handler(request: Request, exc: Exception) -> JSONResponse:
    trace_id = request.headers.get("X-Trace-Id", "")
    return JSONResponse(
        status_code=500,
        content=_error_payload(
            500,
            "FMU_SERVICE_ERROR",
            f"{type(exc).__name__}: {exc}",
            trace_id,
        ),
    )


@app.get("/health")
def health(request: Request) -> Any:
    manager = getattr(request.app.state, "manager", None)
    if manager is None:
        startup_error = getattr(request.app.state, "startup_error", None)
        return JSONResponse(
            status_code=503,
            content={
                "status": "DOWN",
                "ready": False,
                "errorCode": "FMU_SERVICE_NOT_READY",
                "message": startup_error or "FMU service is not ready",
            },
        )
    return manager.health()


@app.get("/fmu/metadata")
@app.get("/parameters", include_in_schema=False)
def metadata(request: Request) -> dict[str, Any]:
    return _manager(request).metadata()


@app.post("/fmu/validate")
def validate_fmu(request: Request) -> dict[str, Any]:
    return _manager(request).validate_fmu()


@app.post("/step-fleet")
@app.post("/api/fleet/step", include_in_schema=False)
def step_fleet(
    payload: StepFleetRequestPayload,
    request: Request,
) -> dict[str, Any]:
    try:
        response = _manager(request).step_fleet(payload.to_domain())
    except FmuProtocolError as exc:
        exc.trace_id = payload.trace_id
        raise
    return step_fleet_response_to_dict(response)


@app.delete("/instances/{trainId}")
def delete_instance(trainId: str, request: Request) -> dict[str, Any]:
    return _manager(request).delete_instance(trainId)


@app.post("/instances/{trainId}/reset")
def reset_instance(
    trainId: str,
    payload: StepFleetRequestPayload,
    request: Request,
) -> dict[str, Any]:
    if len(payload.trains) != 1:
        raise FmuProtocolError(
            400,
            "INVALID_REQUEST",
            "instance reset requires exactly one train in the fleet request",
        )
    train = payload.trains[0]
    if train.train_id != trainId:
        raise FmuProtocolError(
            400,
            "INVALID_REQUEST",
            "path trainId must match the reset request trainId",
        )
    if train.lifecycle_command != "RESET":
        raise FmuProtocolError(
            400,
            "INVALID_LIFECYCLE_COMMAND",
            "instance reset endpoint requires lifecycleCommand=RESET",
        )
    try:
        response = _manager(request).step_fleet(payload.to_domain())
    except FmuProtocolError as exc:
        exc.trace_id = payload.trace_id
        raise
    return step_fleet_response_to_dict(response)


@app.post("/instances/reset-all")
def reset_all_instances(request: Request) -> dict[str, Any]:
    return _manager(request).reset_all()


def openapi_json() -> str:
    """Small deterministic probe used by container smoke tests."""
    return json.dumps(app.openapi(), ensure_ascii=False, sort_keys=True)
