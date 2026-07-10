from __future__ import annotations

from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
from typing import Any

import yaml


@dataclass(frozen=True)
class TractionParameters:
    max_power_watts: float
    max_traction_force_newtons: float
    efficiency: float


@dataclass(frozen=True)
class BrakeParameters:
    max_service_brake_force_newtons: float
    max_emergency_brake_force_newtons: float
    regen_brake_ratio: float
    regen_efficiency: float


@dataclass(frozen=True)
class ResistanceParameters:
    davis_a: float
    davis_b: float
    davis_c: float


@dataclass(frozen=True)
class PowerParameters:
    nominal_voltage: float
    min_voltage: float
    cutoff_voltage: float


@dataclass(frozen=True)
class VehicleParameters:
    train_type: str
    length_meters: float
    empty_mass_kg: float
    max_load_mass_kg: float
    traction: TractionParameters
    brake: BrakeParameters
    resistance: ResistanceParameters
    power: PowerParameters
    parameter_set_id: str
    source_path: Path


def load_vehicle_parameters(configured_path: str | Path | None = None) -> VehicleParameters:
    source_path = _resolve_path(configured_path)
    raw_bytes = source_path.read_bytes()
    try:
        document = yaml.safe_load(raw_bytes)
    except yaml.YAMLError as exc:
        raise ValueError(f"Invalid vehicle parameter YAML in {source_path}: {exc}") from exc
    if not isinstance(document, dict):
        _invalid(source_path, "$", "document must be a mapping")

    _reject_unknown_keys(
        document,
        source_path,
        "$",
        {"trainType", "lengthMeters", "emptyMassKg", "maxLoadMassKg", "traction", "brake", "resistance", "power"},
    )

    train_type = _text(document, source_path, "trainType")
    length_meters = _positive(document, source_path, "lengthMeters")
    empty_mass_kg = _positive(document, source_path, "emptyMassKg")
    max_load_mass_kg = _positive(document, source_path, "maxLoadMassKg")
    traction = _section(document, source_path, "traction")
    brake = _section(document, source_path, "brake")
    resistance = _section(document, source_path, "resistance")
    power = _section(document, source_path, "power")
    _reject_unknown_keys(traction, source_path, "traction", {"maxPowerWatts", "maxTractionForceNewtons", "efficiency"})
    _reject_unknown_keys(
        brake,
        source_path,
        "brake",
        {"maxServiceBrakeForceNewtons", "maxEmergencyBrakeForceNewtons", "regenBrakeRatio", "regenEfficiency"},
    )
    _reject_unknown_keys(resistance, source_path, "resistance", {"davisA", "davisB", "davisC"})
    _reject_unknown_keys(power, source_path, "power", {"nominalVoltage", "minVoltage", "cutoffVoltage"})

    traction_efficiency = _efficiency(traction, source_path, "traction.efficiency", "efficiency")
    regen_efficiency = _efficiency(brake, source_path, "brake.regenEfficiency", "regenEfficiency")
    regen_ratio = _range(brake, source_path, "brake.regenBrakeRatio", "regenBrakeRatio", 0, 1)
    nominal_voltage = _positive(power, source_path, "power.nominalVoltage", "nominalVoltage")
    min_voltage = _positive(power, source_path, "power.minVoltage", "minVoltage")
    cutoff_voltage = _positive(power, source_path, "power.cutoffVoltage", "cutoffVoltage")
    if not cutoff_voltage < min_voltage <= nominal_voltage:
        _invalid(source_path, "power", "must satisfy cutoffVoltage < minVoltage <= nominalVoltage")

    return VehicleParameters(
        train_type=train_type,
        length_meters=length_meters,
        empty_mass_kg=empty_mass_kg,
        max_load_mass_kg=max_load_mass_kg,
        traction=TractionParameters(
            max_power_watts=_positive(traction, source_path, "traction.maxPowerWatts", "maxPowerWatts"),
            max_traction_force_newtons=_positive(
                traction,
                source_path,
                "traction.maxTractionForceNewtons",
                "maxTractionForceNewtons",
            ),
            efficiency=traction_efficiency,
        ),
        brake=BrakeParameters(
            max_service_brake_force_newtons=_positive(
                brake,
                source_path,
                "brake.maxServiceBrakeForceNewtons",
                "maxServiceBrakeForceNewtons",
            ),
            max_emergency_brake_force_newtons=_positive(
                brake,
                source_path,
                "brake.maxEmergencyBrakeForceNewtons",
                "maxEmergencyBrakeForceNewtons",
            ),
            regen_brake_ratio=regen_ratio,
            regen_efficiency=regen_efficiency,
        ),
        resistance=ResistanceParameters(
            davis_a=_non_negative(resistance, source_path, "resistance.davisA", "davisA"),
            davis_b=_non_negative(resistance, source_path, "resistance.davisB", "davisB"),
            davis_c=_non_negative(resistance, source_path, "resistance.davisC", "davisC"),
        ),
        power=PowerParameters(
            nominal_voltage=nominal_voltage,
            min_voltage=min_voltage,
            cutoff_voltage=cutoff_voltage,
        ),
        parameter_set_id="sha256:" + hashlib.sha256(raw_bytes).hexdigest(),
        source_path=source_path,
    )


def _resolve_path(configured_path: str | Path | None) -> Path:
    raw_path = configured_path or os.environ.get("TRAIN_PARAMS_PATH", "config/train_params.yaml")
    requested = Path(raw_path).expanduser()
    if requested.is_absolute():
        if requested.is_file():
            return requested.resolve()
        raise FileNotFoundError(f"Vehicle parameter file does not exist: {requested}")

    working_directory = Path.cwd()
    candidates = (
        (working_directory / requested).resolve(),
        (working_directory.parent / requested).resolve(),
        (Path(__file__).resolve().parents[2] / requested).resolve(),
    )
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise FileNotFoundError(f"Vehicle parameter file does not exist; checked {list(candidates)}")


def _section(document: dict[str, Any], path: Path, field: str) -> dict[str, Any]:
    value = document.get(field)
    if not isinstance(value, dict):
        _invalid(path, field, "section is required")
    return value


def _reject_unknown_keys(
    document: dict[str, Any],
    path: Path,
    field: str,
    allowed_keys: set[str],
) -> None:
    unknown = sorted(set(document) - allowed_keys)
    if unknown:
        _invalid(path, field, f"contains unknown fields {unknown}")


def _text(document: dict[str, Any], path: Path, field: str) -> str:
    value = document.get(field)
    if not isinstance(value, str) or not value.strip():
        _invalid(path, field, "must not be blank")
    return value


def _number(document: dict[str, Any], path: Path, field_path: str, key: str | None = None) -> float:
    value = document.get(key or field_path)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        _invalid(path, field_path, "must be numeric")
    result = float(value)
    if result != result or result in (float("inf"), float("-inf")):
        _invalid(path, field_path, "must be finite")
    return result


def _positive(document: dict[str, Any], path: Path, field_path: str, key: str | None = None) -> float:
    value = _number(document, path, field_path, key)
    if value <= 0:
        _invalid(path, field_path, "must be greater than 0")
    return value


def _non_negative(document: dict[str, Any], path: Path, field_path: str, key: str) -> float:
    value = _number(document, path, field_path, key)
    if value < 0:
        _invalid(path, field_path, "must be greater than or equal to 0")
    return value


def _efficiency(document: dict[str, Any], path: Path, field_path: str, key: str) -> float:
    value = _number(document, path, field_path, key)
    if value <= 0 or value > 1:
        _invalid(path, field_path, "must be in (0, 1]")
    return value


def _range(
    document: dict[str, Any],
    path: Path,
    field_path: str,
    key: str,
    minimum: float,
    maximum: float,
) -> float:
    value = _number(document, path, field_path, key)
    if value < minimum or value > maximum:
        _invalid(path, field_path, f"must be in [{minimum}, {maximum}]")
    return value


def _invalid(path: Path, field: str, reason: str) -> None:
    raise ValueError(f"Invalid vehicle parameter {field} in {path}: {reason}")
