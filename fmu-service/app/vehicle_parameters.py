from __future__ import annotations

from dataclasses import dataclass
import hashlib
import math
import os
from pathlib import Path
import struct
from typing import Any

import yaml


CURVE_POINT_COUNT = 52
CURVE_POWER_RELATIVE_TOLERANCE = 0.005
EFFICIENCY_ABSOLUTE_TOLERANCE = 0.01


@dataclass(frozen=True)
class LoadCases:
    aw0: float
    aw2: float
    aw3: float


@dataclass(frozen=True)
class CarMass:
    aw0: float
    aw2: float
    aw3: float
    hard_limit: float


@dataclass(frozen=True)
class FormationParameters:
    order: tuple[str, ...]
    motor_count: int
    axle_count: int
    hard_mass_limit_kg: float
    load_cases_kg: LoadCases
    car_mass_kg: dict[str, CarMass]


@dataclass(frozen=True)
class DrivetrainParameters:
    motor_count: int
    gear_ratio: float
    wheel_radius_meters: float
    traction_total_efficiency: float
    regen_total_efficiency: float


@dataclass(frozen=True)
class CurveParameters:
    speed_rpm: tuple[float, ...]
    traction_torque_nm_per_motor: tuple[float, ...]
    brake_torque_nm_per_motor: tuple[float, ...]
    reference_traction_current_amps: tuple[float, ...]
    reference_brake_current_amps: tuple[float, ...]
    reference_traction_mechanical_power_kw_per_motor: tuple[float, ...]
    reference_brake_mechanical_power_kw_per_motor: tuple[float, ...]
    reference_voltage_volts: float

    @property
    def point_count(self) -> int:
        return len(self.speed_rpm)

    def arrays(self) -> tuple[tuple[float, ...], ...]:
        return (
            self.speed_rpm,
            self.traction_torque_nm_per_motor,
            self.brake_torque_nm_per_motor,
            self.reference_traction_current_amps,
            self.reference_brake_current_amps,
            self.reference_traction_mechanical_power_kw_per_motor,
            self.reference_brake_mechanical_power_kw_per_motor,
        )


@dataclass(frozen=True)
class ResistanceParameters:
    davis_mass_coefficient: float
    davis_axle_constant: float
    davis_speed_mass_coefficient: float
    davis_aero_base: float
    davis_aero_vehicle_coefficient: float
    frontal_area_square_meters: float

    def force_newtons(
        self,
        train_mass_kg: float,
        axle_count: int,
        vehicle_count: int,
        speed_meters_per_second: float,
    ) -> float:
        mass_tonnes = train_mass_kg / 1000.0
        speed_kph = max(0.0, speed_meters_per_second) * 3.6
        return (
            self.davis_mass_coefficient * mass_tonnes
            + self.davis_axle_constant * axle_count
            + self.davis_speed_mass_coefficient * mass_tonnes * speed_kph
            + (
                self.davis_aero_base
                + self.davis_aero_vehicle_coefficient * (vehicle_count - 1)
            )
            * self.frontal_area_square_meters
            * speed_kph**2
        )


@dataclass(frozen=True)
class BrakeParameters:
    service_deceleration_mps2: float
    emergency_deceleration_mps2: float


@dataclass(frozen=True)
class PowerParameters:
    nominal_voltage: float
    min_voltage: float
    cutoff_voltage: float


@dataclass(frozen=True)
class VehicleParameters:
    parameter_schema_version: str
    train_type: str
    length_meters: float
    formation: FormationParameters
    drivetrain: DrivetrainParameters
    curves: CurveParameters
    resistance: ResistanceParameters
    brake: BrakeParameters
    power: PowerParameters
    parameter_set_id: str
    curve_set_id: str
    source_path: Path

    @property
    def empty_mass_kg(self) -> float:
        return self.formation.load_cases_kg.aw0

    @property
    def max_load_mass_kg(self) -> float:
        return self.formation.load_cases_kg.aw2 - self.formation.load_cases_kg.aw0

    @property
    def max_operating_load_mass_kg(self) -> float:
        return self.formation.load_cases_kg.aw3 - self.formation.load_cases_kg.aw0

    @property
    def max_curve_mechanical_traction_power_watts(self) -> float:
        return (
            max(self.curves.reference_traction_mechanical_power_kw_per_motor)
            * 1000.0
            * self.drivetrain.motor_count
        )

    @property
    def max_curve_traction_force_newtons(self) -> float:
        return (
            max(self.curves.traction_torque_nm_per_motor)
            * self.drivetrain.motor_count
            * self.drivetrain.gear_ratio
            / self.drivetrain.wheel_radius_meters
        )


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
        {
            "parameterSchemaVersion",
            "trainType",
            "lengthMeters",
            "formation",
            "drivetrain",
            "curves",
            "resistance",
            "brake",
            "power",
        },
    )
    schema_version = _text(document, source_path, "parameterSchemaVersion")
    if schema_version != "2":
        _invalid(source_path, "parameterSchemaVersion", 'must equal "2"')

    formation_doc = _section(document, source_path, "formation")
    drivetrain_doc = _section(document, source_path, "drivetrain")
    curves_doc = _section(document, source_path, "curves")
    resistance_doc = _section(document, source_path, "resistance")
    brake_doc = _section(document, source_path, "brake")
    power_doc = _section(document, source_path, "power")
    formation = _parse_formation(formation_doc, source_path)
    drivetrain = _parse_drivetrain(drivetrain_doc, formation, source_path)
    curves = _parse_curves(curves_doc, drivetrain, source_path)
    resistance = _parse_resistance(resistance_doc, source_path)
    brake = _parse_brake(brake_doc, source_path)
    power = _parse_power(power_doc, source_path)

    return VehicleParameters(
        parameter_schema_version=schema_version,
        train_type=_text(document, source_path, "trainType"),
        length_meters=_positive(document, source_path, "lengthMeters"),
        formation=formation,
        drivetrain=drivetrain,
        curves=curves,
        resistance=resistance,
        brake=brake,
        power=power,
        parameter_set_id="sha256:" + hashlib.sha256(raw_bytes).hexdigest(),
        curve_set_id=curve_set_id(curves),
        source_path=source_path,
    )


def _parse_formation(document: dict[str, Any], path: Path) -> FormationParameters:
    _reject_unknown_keys(
        document,
        path,
        "formation",
        {"order", "motorCount", "axleCount", "hardMassLimitKg", "loadCasesKg", "carMassKg"},
    )
    order_value = document.get("order")
    if not isinstance(order_value, list) or not order_value or not all(
        isinstance(value, str) and value for value in order_value
    ):
        _invalid(path, "formation.order", "must be a nonempty string array")
    motor_count = _positive_integer(document, path, "formation.motorCount", "motorCount")
    axle_count = _positive_integer(document, path, "formation.axleCount", "axleCount")
    hard_mass_limit = _positive(document, path, "formation.hardMassLimitKg", "hardMassLimitKg")
    loads_doc = _section(document, path, "loadCasesKg", "formation.loadCasesKg")
    _reject_unknown_keys(loads_doc, path, "formation.loadCasesKg", {"AW0", "AW2", "AW3"})
    loads = LoadCases(
        aw0=_positive(loads_doc, path, "formation.loadCasesKg.AW0", "AW0"),
        aw2=_positive(loads_doc, path, "formation.loadCasesKg.AW2", "AW2"),
        aw3=_positive(loads_doc, path, "formation.loadCasesKg.AW3", "AW3"),
    )
    if not loads.aw0 < loads.aw2 < loads.aw3 <= hard_mass_limit:
        _invalid(path, "formation.loadCasesKg", "must satisfy AW0 < AW2 < AW3 <= hardMassLimitKg")

    cars_doc = _section(document, path, "carMassKg", "formation.carMassKg")
    if set(cars_doc) != {"Tc", "M"}:
        _invalid(path, "formation.carMassKg", "must contain exactly Tc and M")
    cars: dict[str, CarMass] = {}
    for name, raw_car in cars_doc.items():
        if not isinstance(raw_car, dict):
            _invalid(path, f"formation.carMassKg.{name}", "must be a mapping")
        _reject_unknown_keys(raw_car, path, f"formation.carMassKg.{name}", {"AW0", "AW2", "AW3", "hardLimit"})
        car = CarMass(
            aw0=_positive(raw_car, path, f"formation.carMassKg.{name}.AW0", "AW0"),
            aw2=_positive(raw_car, path, f"formation.carMassKg.{name}.AW2", "AW2"),
            aw3=_positive(raw_car, path, f"formation.carMassKg.{name}.AW3", "AW3"),
            hard_limit=_positive(raw_car, path, f"formation.carMassKg.{name}.hardLimit", "hardLimit"),
        )
        if not car.aw0 < car.aw2 < car.aw3 <= car.hard_limit:
            _invalid(path, f"formation.carMassKg.{name}", "must satisfy AW0 < AW2 < AW3 <= hardLimit")
        cars[name] = car
    if any(name not in cars for name in order_value):
        _invalid(path, "formation.order", "contains an unknown car type")
    for label, expected in (("aw0", loads.aw0), ("aw2", loads.aw2), ("aw3", loads.aw3)):
        actual = sum(getattr(cars[name], label) for name in order_value)
        if not math.isclose(actual, expected, abs_tol=1e-6):
            _invalid(path, f"formation.loadCasesKg.{label.upper()}", f"does not equal per-car sum {actual}")
    hard_sum = sum(cars[name].hard_limit for name in order_value)
    if not math.isclose(hard_sum, hard_mass_limit, abs_tol=1e-6):
        _invalid(path, "formation.hardMassLimitKg", "does not equal per-car hard-limit sum")
    return FormationParameters(
        order=tuple(order_value),
        motor_count=motor_count,
        axle_count=axle_count,
        hard_mass_limit_kg=hard_mass_limit,
        load_cases_kg=loads,
        car_mass_kg=cars,
    )


def _parse_drivetrain(
    document: dict[str, Any],
    formation: FormationParameters,
    path: Path,
) -> DrivetrainParameters:
    _reject_unknown_keys(
        document,
        path,
        "drivetrain",
        {"motorCount", "gearRatio", "wheelRadiusMeters", "tractionTotalEfficiency", "regenTotalEfficiency"},
    )
    motor_count = _positive_integer(document, path, "drivetrain.motorCount", "motorCount")
    if motor_count != formation.motor_count:
        _invalid(path, "drivetrain.motorCount", "must equal formation.motorCount")
    return DrivetrainParameters(
        motor_count=motor_count,
        gear_ratio=_positive(document, path, "drivetrain.gearRatio", "gearRatio"),
        wheel_radius_meters=_positive(document, path, "drivetrain.wheelRadiusMeters", "wheelRadiusMeters"),
        traction_total_efficiency=_efficiency(
            document, path, "drivetrain.tractionTotalEfficiency", "tractionTotalEfficiency"
        ),
        regen_total_efficiency=_efficiency(
            document, path, "drivetrain.regenTotalEfficiency", "regenTotalEfficiency"
        ),
    )


def _parse_curves(
    document: dict[str, Any],
    drivetrain: DrivetrainParameters,
    path: Path,
) -> CurveParameters:
    names = {
        "speedRpm",
        "tractionTorqueNmPerMotor",
        "brakeTorqueNmPerMotor",
        "referenceTractionCurrentAmps",
        "referenceBrakeCurrentAmps",
        "referenceTractionMechanicalPowerKwPerMotor",
        "referenceBrakeMechanicalPowerKwPerMotor",
        "referenceVoltageVolts",
    }
    _reject_unknown_keys(document, path, "curves", names)
    arrays = {
        name: _curve_array(document, path, f"curves.{name}", name)
        for name in names
        if name != "referenceVoltageVolts"
    }
    speed = arrays["speedRpm"]
    duplicate_indexes: list[int] = []
    for index in range(1, len(speed)):
        if speed[index] < speed[index - 1]:
            _invalid(path, f"curves.speedRpm[{index}]", "must be nondecreasing")
        if speed[index] == speed[index - 1]:
            duplicate_indexes.append(index)
    if duplicate_indexes != [CURVE_POINT_COUNT - 1]:
        _invalid(path, "curves.speedRpm", "must preserve only the duplicated terminal source point")
    for name, values in arrays.items():
        if values[-1] != values[-2]:
            _invalid(path, f"curves.{name}[51]", "must match across terminal duplicate speed")
    reference_voltage = _positive(document, path, "curves.referenceVoltageVolts", "referenceVoltageVolts")
    curves = CurveParameters(
        speed_rpm=speed,
        traction_torque_nm_per_motor=arrays["tractionTorqueNmPerMotor"],
        brake_torque_nm_per_motor=arrays["brakeTorqueNmPerMotor"],
        reference_traction_current_amps=arrays["referenceTractionCurrentAmps"],
        reference_brake_current_amps=arrays["referenceBrakeCurrentAmps"],
        reference_traction_mechanical_power_kw_per_motor=arrays[
            "referenceTractionMechanicalPowerKwPerMotor"
        ],
        reference_brake_mechanical_power_kw_per_motor=arrays[
            "referenceBrakeMechanicalPowerKwPerMotor"
        ],
        reference_voltage_volts=reference_voltage,
    )
    _validate_curve_physics(curves, drivetrain, path)
    return curves


def _validate_curve_physics(curves: CurveParameters, drivetrain: DrivetrainParameters, path: Path) -> None:
    for index, rpm in enumerate(curves.speed_rpm):
        omega = rpm * 2.0 * math.pi / 60.0
        for label, torque, reference_kw in (
            (
                "referenceTractionMechanicalPowerKwPerMotor",
                curves.traction_torque_nm_per_motor[index],
                curves.reference_traction_mechanical_power_kw_per_motor[index],
            ),
            (
                "referenceBrakeMechanicalPowerKwPerMotor",
                curves.brake_torque_nm_per_motor[index],
                curves.reference_brake_mechanical_power_kw_per_motor[index],
            ),
        ):
            calculated_kw = torque * omega / 1000.0
            if reference_kw or calculated_kw:
                relative_error = abs(calculated_kw - reference_kw) / max(reference_kw, 1e-9)
                if relative_error > CURVE_POWER_RELATIVE_TOLERANCE:
                    _invalid(path, f"curves.{label}[{index}]", f"torque/rpm power mismatch {relative_error}")
        traction_mechanical = (
            curves.reference_traction_mechanical_power_kw_per_motor[index]
            * 1000.0
            * drivetrain.motor_count
        )
        traction_grid = curves.reference_traction_current_amps[index] * curves.reference_voltage_volts
        if traction_mechanical > 0 and traction_grid > 0:
            efficiency = traction_mechanical / traction_grid
            if abs(efficiency - drivetrain.traction_total_efficiency) > EFFICIENCY_ABSOLUTE_TOLERANCE:
                _invalid(path, f"curves.referenceTractionCurrentAmps[{index}]", f"inconsistent efficiency {efficiency}")
        brake_mechanical = (
            curves.reference_brake_mechanical_power_kw_per_motor[index]
            * 1000.0
            * drivetrain.motor_count
        )
        brake_grid = curves.reference_brake_current_amps[index] * curves.reference_voltage_volts
        if brake_mechanical > 0 and brake_grid > 0:
            efficiency = brake_grid / brake_mechanical
            if abs(efficiency - drivetrain.regen_total_efficiency) > EFFICIENCY_ABSOLUTE_TOLERANCE:
                _invalid(path, f"curves.referenceBrakeCurrentAmps[{index}]", f"inconsistent efficiency {efficiency}")


def _parse_resistance(document: dict[str, Any], path: Path) -> ResistanceParameters:
    names = {
        "davisMassCoefficient",
        "davisAxleConstant",
        "davisSpeedMassCoefficient",
        "davisAeroBase",
        "davisAeroVehicleCoefficient",
        "frontalAreaSquareMeters",
    }
    _reject_unknown_keys(document, path, "resistance", names)
    return ResistanceParameters(
        davis_mass_coefficient=_non_negative(document, path, "resistance.davisMassCoefficient", "davisMassCoefficient"),
        davis_axle_constant=_non_negative(document, path, "resistance.davisAxleConstant", "davisAxleConstant"),
        davis_speed_mass_coefficient=_non_negative(
            document, path, "resistance.davisSpeedMassCoefficient", "davisSpeedMassCoefficient"
        ),
        davis_aero_base=_non_negative(document, path, "resistance.davisAeroBase", "davisAeroBase"),
        davis_aero_vehicle_coefficient=_non_negative(
            document, path, "resistance.davisAeroVehicleCoefficient", "davisAeroVehicleCoefficient"
        ),
        frontal_area_square_meters=_positive(
            document, path, "resistance.frontalAreaSquareMeters", "frontalAreaSquareMeters"
        ),
    )


def _parse_brake(document: dict[str, Any], path: Path) -> BrakeParameters:
    _reject_unknown_keys(document, path, "brake", {"serviceDecelerationMps2", "emergencyDecelerationMps2"})
    service = _positive(document, path, "brake.serviceDecelerationMps2", "serviceDecelerationMps2")
    emergency = _positive(document, path, "brake.emergencyDecelerationMps2", "emergencyDecelerationMps2")
    if emergency < service:
        _invalid(path, "brake", "emergencyDecelerationMps2 must be >= serviceDecelerationMps2")
    return BrakeParameters(service, emergency)


def _parse_power(document: dict[str, Any], path: Path) -> PowerParameters:
    _reject_unknown_keys(document, path, "power", {"nominalVoltage", "minVoltage", "cutoffVoltage"})
    power = PowerParameters(
        nominal_voltage=_positive(document, path, "power.nominalVoltage", "nominalVoltage"),
        min_voltage=_positive(document, path, "power.minVoltage", "minVoltage"),
        cutoff_voltage=_positive(document, path, "power.cutoffVoltage", "cutoffVoltage"),
    )
    if not power.cutoff_voltage < power.min_voltage <= power.nominal_voltage:
        _invalid(path, "power", "must satisfy cutoffVoltage < minVoltage <= nominalVoltage")
    return power


def curve_set_id(curves: CurveParameters) -> str:
    digest = hashlib.sha256()
    for values in curves.arrays():
        digest.update(struct.pack(">I", len(values)))
        for value in values:
            digest.update(struct.pack(">d", value))
    digest.update(struct.pack(">d", curves.reference_voltage_volts))
    return "sha256:" + digest.hexdigest()


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


def _section(
    document: dict[str, Any],
    path: Path,
    key: str,
    field_path: str | None = None,
) -> dict[str, Any]:
    value = document.get(key)
    if not isinstance(value, dict):
        _invalid(path, field_path or key, "section is required")
    return value


def _reject_unknown_keys(
    document: dict[str, Any], path: Path, field: str, allowed_keys: set[str]
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
    if not math.isfinite(result):
        _invalid(path, field_path, "must be finite")
    return result


def _positive(document: dict[str, Any], path: Path, field_path: str, key: str | None = None) -> float:
    value = _number(document, path, field_path, key)
    if value <= 0:
        _invalid(path, field_path, "must be greater than 0")
    return value


def _positive_integer(document: dict[str, Any], path: Path, field_path: str, key: str) -> int:
    value = document.get(key)
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        _invalid(path, field_path, "must be a positive integer")
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


def _curve_array(document: dict[str, Any], path: Path, field_path: str, key: str) -> tuple[float, ...]:
    raw = document.get(key)
    if not isinstance(raw, list) or len(raw) != CURVE_POINT_COUNT:
        _invalid(path, field_path, f"must contain exactly {CURVE_POINT_COUNT} points")
    result: list[float] = []
    for index, value in enumerate(raw):
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            _invalid(path, f"{field_path}[{index}]", "must be numeric")
        numeric = float(value)
        if not math.isfinite(numeric) or numeric < 0:
            _invalid(path, f"{field_path}[{index}]", "must be finite and nonnegative")
        result.append(numeric)
    return tuple(result)


def _invalid(path: Path, field: str, reason: str) -> None:
    raise ValueError(f"Invalid vehicle parameter {field} in {path}: {reason}")
