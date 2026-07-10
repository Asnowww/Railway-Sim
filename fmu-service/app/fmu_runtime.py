from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
from importlib import metadata as importlib_metadata
import json
import os
from pathlib import Path
import platform
import sys
import tempfile
from types import MappingProxyType
from typing import Any, Mapping
from uuid import uuid4
import zipfile

from fmpy import extract, read_model_description
from fmpy.fmi2 import FMU2Slave
from jsonschema import Draft202012Validator
import yaml

from .vehicle_parameters import VehicleParameters


@dataclass(frozen=True)
class VariableBinding:
    logical_name: str
    fmi_name: str
    value_reference: int
    value_type: str
    unit: str | None
    causality: str
    variability: str


class FmuArtifactError(RuntimeError):
    pass


class FmuModelRuntime:
    """Validated, process-wide FMU artifact and read-only variable bindings."""

    def __init__(
        self,
        parameters: VehicleParameters,
        fmu_path: str | Path | None = None,
        manifest_path: str | Path | None = None,
        mapping_path: str | Path | None = None,
    ) -> None:
        self.parameters = parameters
        self.fmu_path = self._resolve_path(
            fmu_path or os.environ.get("FMU_PATH"),
            Path(__file__).resolve().parents[1] / "build" / "TrainTractionBrake.fmu",
            "FMU artifact",
        )
        self.manifest_path = self._resolve_path(
            manifest_path or os.environ.get("FMU_MANIFEST_PATH"),
            self.fmu_path.with_name("fmu-manifest.json"),
            "FMU manifest",
        )
        self.mapping_path = self._resolve_path(
            mapping_path or os.environ.get("FMU_MAPPING_PATH"),
            Path(__file__).resolve().parents[2] / "config" / "fmu_mapping.yaml",
            "FMU mapping",
        )
        self.manifest_schema_path = self._resolve_path(
            os.environ.get("FMU_MANIFEST_SCHEMA_PATH"),
            Path(__file__).resolve().parents[1] / "fmu-manifest.schema.json",
            "FMU manifest schema",
        )

        self.manifest = self._load_json(self.manifest_path)
        self.mapping = self._load_yaml(self.mapping_path)
        self.model_description = read_model_description(str(self.fmu_path), validate=True)
        self.model_version = str(self.mapping.get("modelVersion", ""))
        self._variables = MappingProxyType(
            {variable.name: variable for variable in self.model_description.modelVariables}
        )
        self._bindings, self.validation_report = self._validate_artifact()

        self._extraction = tempfile.TemporaryDirectory(prefix="railway-fmu-")
        extract(str(self.fmu_path), self._extraction.name)
        self.unzip_directory = self._extraction.name
        self.loaded_at = datetime.now(timezone.utc).isoformat()

    @staticmethod
    def _resolve_path(
        configured: str | Path | None,
        default: Path,
        label: str,
    ) -> Path:
        path = Path(configured).expanduser() if configured else default
        resolved = path.resolve()
        if not resolved.is_file():
            raise FileNotFoundError(f"{label} does not exist: {resolved}")
        return resolved

    @staticmethod
    def _load_json(path: Path) -> dict[str, Any]:
        document = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(document, dict):
            raise FmuArtifactError(f"JSON document must be an object: {path}")
        return document

    @staticmethod
    def _load_yaml(path: Path) -> dict[str, Any]:
        document = yaml.safe_load(path.read_text(encoding="utf-8"))
        if not isinstance(document, dict):
            raise FmuArtifactError(f"YAML document must be a mapping: {path}")
        return document

    def _validate_artifact(
        self,
    ) -> tuple[Mapping[str, Mapping[str, VariableBinding]], Mapping[str, Any]]:
        schema = self._load_json(self.manifest_schema_path)
        errors = sorted(
            Draft202012Validator(schema).iter_errors(self.manifest),
            key=lambda error: list(error.absolute_path),
        )
        if errors:
            rendered = "; ".join(error.message for error in errors)
            raise FmuArtifactError(f"FMU manifest validation failed: {rendered}")

        actual_hash = "sha256:" + hashlib.sha256(self.fmu_path.read_bytes()).hexdigest()
        if self.manifest.get("fmuSha256") != actual_hash:
            raise FmuArtifactError(
                "FMU SHA-256 does not match manifest: "
                f"actual={actual_hash}, manifest={self.manifest.get('fmuSha256')}"
            )
        if self.manifest.get("parameterSetId") != self.parameters.parameter_set_id:
            raise FmuArtifactError(
                "FMU manifest parameterSetId does not match loaded train parameters"
            )
        if self.manifest.get("modelVersion") != self.model_version:
            raise FmuArtifactError("FMU manifest modelVersion does not match fmu_mapping.yaml")

        description = self.model_description
        if description.fmiVersion != "2.0":
            raise FmuArtifactError(f"Expected FMI 2.0, got {description.fmiVersion}")
        if description.coSimulation is None:
            raise FmuArtifactError("FMU does not declare FMI 2.0 Co-Simulation")
        if getattr(description.coSimulation, "canBeInstantiatedOnlyOncePerProcess", False) is True:
            raise FmuArtifactError("FMU forbids multiple instances in one process")
        if self.manifest.get("fmiVersion") != description.fmiVersion:
            raise FmuArtifactError("FMU manifest FMI version does not match modelDescription.xml")
        if self.manifest.get("fmiType") != "CoSimulation":
            raise FmuArtifactError("FMU manifest must declare CoSimulation")

        model_identifier = description.coSimulation.modelIdentifier
        linux_binary = f"binaries/linux64/{model_identifier}.so"
        with zipfile.ZipFile(self.fmu_path) as archive:
            if linux_binary not in archive.namelist():
                raise FmuArtifactError(f"FMU is missing required binary {linux_binary}")

        bindings: dict[str, Mapping[str, VariableBinding]] = {}
        used_references: dict[tuple[str, int], str] = {}
        checked_names: list[str] = []
        group_expectations = {
            "parameters": ("parameter", "fixed"),
            "initialState": ("parameter", "fixed"),
            "stepInputs": ("input", None),
            "outputs": ("output", None),
        }
        for group_name, (expected_causality, expected_variability) in group_expectations.items():
            group = self.mapping.get(group_name)
            if not isinstance(group, dict) or not group:
                raise FmuArtifactError(f"FMU mapping group is missing or empty: {group_name}")
            group_bindings: dict[str, VariableBinding] = {}
            for logical_name, specification in group.items():
                if not isinstance(specification, dict):
                    raise FmuArtifactError(
                        f"FMU mapping entry must be a mapping: {group_name}.{logical_name}"
                    )
                fmi_name = specification.get("fmiVariable")
                expected_type = specification.get("type")
                if not isinstance(fmi_name, str) or not isinstance(expected_type, str):
                    raise FmuArtifactError(
                        f"FMU mapping entry lacks fmiVariable/type: {group_name}.{logical_name}"
                    )
                variable = self._variables.get(fmi_name)
                if variable is None:
                    raise FmuArtifactError(f"FMU variable is missing: {fmi_name}")
                if variable.type != expected_type:
                    raise FmuArtifactError(
                        f"FMU variable {fmi_name} type is {variable.type}, expected {expected_type}"
                    )
                if variable.causality != expected_causality:
                    raise FmuArtifactError(
                        f"FMU variable {fmi_name} causality is {variable.causality}, "
                        f"expected {expected_causality}"
                    )
                if expected_variability and variable.variability != expected_variability:
                    raise FmuArtifactError(
                        f"FMU variable {fmi_name} variability is {variable.variability}, "
                        f"expected {expected_variability}"
                    )
                expected_unit = specification.get("unit")
                if expected_unit is not None and variable.unit != expected_unit:
                    raise FmuArtifactError(
                        f"FMU variable {fmi_name} unit is {variable.unit}, expected {expected_unit}"
                    )
                value_reference = int(variable.valueReference)
                reference_key = (variable.type, value_reference)
                previous_name = used_references.get(reference_key)
                if previous_name and previous_name != fmi_name:
                    raise FmuArtifactError(
                        f"Mapped variables {previous_name} and {fmi_name} alias the same "
                        f"{variable.type} value reference {value_reference}"
                    )
                used_references[reference_key] = fmi_name
                group_bindings[logical_name] = VariableBinding(
                    logical_name=logical_name,
                    fmi_name=fmi_name,
                    value_reference=value_reference,
                    value_type=variable.type,
                    unit=variable.unit,
                    causality=variable.causality,
                    variability=variable.variability,
                )
                checked_names.append(fmi_name)
            bindings[group_name] = MappingProxyType(group_bindings)

        report = MappingProxyType(
            {
                "status": "VALID",
                "checkedVariableCount": len(checked_names),
                "checkedVariables": tuple(checked_names),
                "linuxBinary": linux_binary,
                "multipleInstancesAllowed": True,
            }
        )
        return MappingProxyType(bindings), report

    def binding(self, group_name: str, logical_name: str) -> VariableBinding:
        try:
            return self._bindings[group_name][logical_name]
        except KeyError as exc:
            raise FmuArtifactError(
                f"Unknown FMU binding {group_name}.{logical_name}"
            ) from exc

    def group_bindings(self, group_name: str) -> Mapping[str, VariableBinding]:
        try:
            return self._bindings[group_name]
        except KeyError as exc:
            raise FmuArtifactError(f"Unknown FMU binding group {group_name}") from exc

    def parameter_values(self) -> Mapping[str, float]:
        parameters = self.parameters
        values_by_source = {
            "traction.maxPowerWatts": parameters.traction.max_power_watts,
            "traction.maxTractionForceNewtons": parameters.traction.max_traction_force_newtons,
            "traction.efficiency": parameters.traction.efficiency,
            "brake.maxServiceBrakeForceNewtons": parameters.brake.max_service_brake_force_newtons,
            "brake.maxEmergencyBrakeForceNewtons": parameters.brake.max_emergency_brake_force_newtons,
            "brake.regenBrakeRatio": parameters.brake.regen_brake_ratio,
            "brake.regenEfficiency": parameters.brake.regen_efficiency,
            "resistance.davisA": parameters.resistance.davis_a,
            "resistance.davisB": parameters.resistance.davis_b,
            "resistance.davisC": parameters.resistance.davis_c,
            "power.minVoltage": parameters.power.min_voltage,
            "power.cutoffVoltage": parameters.power.cutoff_voltage,
        }
        result: dict[str, float] = {}
        for logical_name, specification in self.mapping["parameters"].items():
            source = specification.get("source")
            if source not in values_by_source:
                raise FmuArtifactError(
                    f"Unsupported parameter source for {logical_name}: {source}"
                )
            result[logical_name] = float(values_by_source[source])
        return MappingProxyType(result)

    def create_slave(self, train_id: str) -> FMU2Slave:
        suffix = hashlib.sha256(train_id.encode("utf-8")).hexdigest()[:16]
        instance_name = f"railway_{suffix}_{uuid4().hex[:8]}"
        return FMU2Slave(
            guid=self.model_description.guid,
            unzipDirectory=self.unzip_directory,
            modelIdentifier=self.model_description.coSimulation.modelIdentifier,
            instanceName=instance_name,
        )

    def metadata(self) -> dict[str, Any]:
        return {
            "modelVersion": self.model_version,
            "modelName": self.model_description.modelName,
            "modelIdentifier": self.model_description.coSimulation.modelIdentifier,
            "fmiVersion": self.model_description.fmiVersion,
            "fmuType": "CoSimulation",
            "fmuSha256": self.manifest["fmuSha256"],
            "parameterSetId": self.parameters.parameter_set_id,
            "parameterSchemaVersion": self.manifest["parameterSchemaVersion"],
            "targetPlatform": self.manifest["targetPlatform"],
            "openModelicaImageDigest": self.manifest["openModelicaImageDigest"],
            "generationTool": self.manifest["generationTool"],
            "sourceCommit": self.manifest["sourceCommit"],
            "builtAt": self.manifest["builtAt"],
            "loadedAt": self.loaded_at,
            "pythonVersion": platform.python_version(),
            "pythonImplementation": platform.python_implementation(),
            "runtimePlatform": f"{sys.platform}/{platform.machine()}",
            "fmpyVersion": importlib_metadata.version("fmpy"),
            "fastapiVersion": importlib_metadata.version("fastapi"),
            "uvicornVersion": importlib_metadata.version("uvicorn"),
            "variableValidation": dict(self.validation_report),
        }

    def validate(self) -> dict[str, Any]:
        _, report = self._validate_artifact()
        return {
            "status": "VALID",
            "modelVersion": self.model_version,
            "parameterSetId": self.parameters.parameter_set_id,
            "fmuSha256": self.manifest["fmuSha256"],
            "variableValidation": dict(report),
        }

    def close(self) -> None:
        self._extraction.cleanup()
