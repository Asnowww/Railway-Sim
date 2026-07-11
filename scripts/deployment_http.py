from __future__ import annotations

import json
import time
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def request_json(
    url: str,
    method: str = "GET",
    payload: dict[str, Any] | list[Any] | None = None,
    timeout: float = 10.0,
    expected_status: int | tuple[int, ...] = 200,
) -> tuple[int, dict[str, Any] | list[Any]]:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    request = Request(
        url,
        data=body,
        method=method,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    expected = (expected_status,) if isinstance(expected_status, int) else expected_status
    try:
        with urlopen(request, timeout=timeout) as response:
            status = response.status
            content = response.read()
    except HTTPError as error:
        status = error.code
        content = error.read()
    if status not in expected:
        text = content.decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {url} returned HTTP {status}: {text}")
    if not content:
        return status, {}
    return status, json.loads(content)


def wait_json(
    url: str,
    predicate,
    timeout_seconds: float = 180.0,
    interval_seconds: float = 2.0,
) -> dict[str, Any] | list[Any]:
    deadline = time.monotonic() + timeout_seconds
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            _, payload = request_json(url, timeout=3.0)
            if predicate(payload):
                return payload
        except (RuntimeError, URLError, TimeoutError, OSError) as error:
            last_error = error
        time.sleep(interval_seconds)
    raise TimeoutError(f"timed out waiting for {url}: {last_error}")


def percentile(values: list[float], percent: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = (len(ordered) - 1) * percent / 100.0
    lower = int(rank)
    upper = min(lower + 1, len(ordered) - 1)
    fraction = rank - lower
    return ordered[lower] * (1.0 - fraction) + ordered[upper] * fraction


def latency_summary(values: list[float]) -> dict[str, float | int]:
    return {
        "samples": len(values),
        "p50Millis": round(percentile(values, 50), 3),
        "p95Millis": round(percentile(values, 95), 3),
        "p99Millis": round(percentile(values, 99), 3),
        "maxMillis": round(max(values, default=0.0), 3),
    }
