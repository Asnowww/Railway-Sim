from __future__ import annotations

from dataclasses import dataclass, field
import hashlib
import json
from datetime import datetime, timezone
import math
from typing import Any


NOMINAL_DC_VOLTAGE = 750.0
MINIMUM_DC_VOLTAGE = 550.0
CONTACT_RAIL_OHM_PER_KM = 0.018
RUNNING_RAIL_OHM_PER_KM = 0.012
MEDIUM_VOLTAGE_KV = 10.0


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def normalized_state(value: str | None, fallback: str) -> str:
    return fallback if value is None or value == "" else value.upper()


def positive_float(value: Any, fallback: float) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return fallback
    return parsed if parsed > 0 else fallback


@dataclass
class MediumVoltageBus:
    id: str
    source_name: str
    voltage_kv: float = MEDIUM_VOLTAGE_KV
    state: str = "ENERGIZED"
    current_amps: float = 0.0
    voltage_drop_percent: float = 0.0


@dataclass
class RingFeeder:
    id: str
    from_bus_id: str
    to_bus_id: str
    state: str = "CLOSED"
    thermal_limit_amps: float = 700.0
    length_km: float = 1.0
    current_amps: float = 0.0


@dataclass
class DeviceState:
    id: str
    name: str
    device_type: str
    state: str
    available: bool
    affects_section_ids: list[str]
    rated_current_amps: float = 0.0


@dataclass
class SubstationState:
    id: str
    name: str
    supply_mode: str
    availability: str
    devices: list[DeviceState]
    section_ids: list[str]
    primary_bus_id: str
    backup_bus_id: str
    incoming_current_amps: float = 0.0
    bus_voltage_drop_percent: float = 0.0


@dataclass
class ThirdRailState:
    id: str
    power_section_id: str
    start_meters: float
    end_meters: float
    energization_state: str
    feeder_state: str
    recommended_supply_mode: str
    contact_rail_voltage: float = NOMINAL_DC_VOLTAGE
    traction_current_amps: float = 0.0
    traction_power_watts: float = 0.0
    regen_power_watts: float = 0.0
    absorbed_regen_watts: float = 0.0
    unabsorbed_regen_watts: float = 0.0
    regen_budget_watts: float = 0.0
    support_reason: str = "normal double-end supply"


@dataclass
class IsolatorState:
    id: str
    third_rail_section_id: str
    state: str


@dataclass
class StrayMonitorState:
    id: str
    section_id: str
    cabinet_state: str
    polarized_potential_volts: float
    risk_level: str
    risk_reason: str
    suggested_action: str


@dataclass
class SectionLoad:
    power_section_id: str
    train_ids: list[str] = field(default_factory=list)
    traction_power_watts: float = 0.0
    regen_power_watts: float = 0.0
    current_amps: float = 0.0


@dataclass
class PowerNetworkModel:
    line_id: str = "beijing-reference"
    line_name: str = "北京地铁抽象供电网络"
    topology_segments: list[dict[str, Any]] = field(default_factory=list)
    nominal_dc_voltage: float = NOMINAL_DC_VOLTAGE
    minimum_dc_voltage: float = MINIMUM_DC_VOLTAGE
    cutoff_dc_voltage: float = 500.0
    max_traction_current_amps: float = 2_000.0
    buses: dict[str, MediumVoltageBus] = field(default_factory=dict)
    feeders: dict[str, RingFeeder] = field(default_factory=dict)
    substations: dict[str, SubstationState] = field(default_factory=dict)
    third_rail_sections: dict[str, ThirdRailState] = field(default_factory=dict)
    isolators: dict[str, IsolatorState] = field(default_factory=dict)
    stray_monitors: dict[str, StrayMonitorState] = field(default_factory=dict)
    section_loads: dict[str, SectionLoad] = field(default_factory=dict)
    section_faults: dict[str, str] = field(default_factory=dict)
    events: list[dict[str, Any]] = field(default_factory=list)
    source_timestamp: str = field(default_factory=now_iso)
    last_step_tick: int | None = None
    active_run_id: str | None = None
    accepted_step_count: int = 0
    last_step_response: dict[str, Any] | None = None
    topology_hash: str = "BUILT_IN_REFERENCE"
    config_hash: str = "BUILT_IN_REFERENCE"
    model_version: str = "POWER_NETWORK_V1"
    parameter_version: str = "POWER_NETWORK_PARAMS_V1"
    bootstrapped: bool = False

    def __post_init__(self) -> None:
        if not self.substations:
            self._load_beijing_reference_model()
            self._append_event("REFERENCE_MODEL_READY", "POWER_NETWORK", self.line_id, "INFO", "loaded Beijing-style default model")

    def bootstrap(self, payload: dict[str, Any]) -> dict[str, Any]:
        self.bootstrapped = True
        self.config_hash = self._sha256({
            key: value for key, value in payload.items() if key != "generatedAt"
        })
        self.topology_hash = self._sha256({
            "topologySegments": payload.get("topologySegments", []),
            "sectionBindings": payload.get("sectionBindings", []),
            "substations": payload.get("substations", []),
            "isolators": payload.get("isolators", []),
        })
        self.line_id = payload.get("lineId", "")
        self.line_name = payload.get("lineName", "")
        self.nominal_dc_voltage = positive_float(payload.get("nominalVoltage"), NOMINAL_DC_VOLTAGE)
        self.minimum_dc_voltage = positive_float(payload.get("minimumVoltage"), MINIMUM_DC_VOLTAGE)
        self.cutoff_dc_voltage = positive_float(payload.get("cutoffVoltage"), self.minimum_dc_voltage * 0.9)
        self.max_traction_current_amps = positive_float(payload.get("maxTractionCurrentAmps"), 2_000.0)
        self.last_step_tick = None
        self.active_run_id = None
        self.accepted_step_count = 0
        self.last_step_response = None
        self.topology_segments = [
            {
                "id": segment.get("id", ""),
                "rawSegmentId": segment.get("rawSegmentId"),
                "startMeters": float(segment.get("startMeters", 0) or 0),
                "endMeters": float(segment.get("endMeters", 0) or 0),
                "fromNodeId": segment.get("fromNodeId", ""),
                "toNodeId": segment.get("toNodeId", ""),
                "track": segment.get("track", ""),
            }
            for segment in payload.get("topologySegments", [])
        ]
        self.buses, self.feeders = self._derive_medium_voltage_network(payload)
        self.substations = {}
        for index, substation in enumerate(payload.get("substations", []), start=1):
            bus_ids = list(self.buses)
            primary_bus_id = bus_ids[(index - 1) % len(bus_ids)] if bus_ids else "BUS-A"
            backup_bus_id = bus_ids[index % len(bus_ids)] if len(bus_ids) > 1 else primary_bus_id
            self.substations[substation["id"]] = SubstationState(
                id=substation["id"],
                name=substation.get("name", substation["id"]),
                supply_mode=substation.get("supplyMode", "DOUBLE_END"),
                availability="AVAILABLE" if substation.get("available", True) else "OUT_OF_SERVICE",
                devices=[
                    DeviceState(
                        id=device["id"],
                        name=device.get("name", device["id"]),
                        device_type=device.get("deviceType", "GENERIC"),
                        state=normalized_state(device.get("defaultState"), "AVAILABLE"),
                        available=normalized_state(device.get("defaultState"), "AVAILABLE") not in {"OUT_OF_SERVICE", "TRIPPED", "OPEN"},
                        affects_section_ids=list(device.get("affectsSectionIds", [])),
                        rated_current_amps=float(device.get("ratedCurrentAmps", 0) or 0),
                    )
                    for device in substation.get("devices", [])
                ],
                section_ids=list(substation.get("sectionIds", [])),
                primary_bus_id=primary_bus_id,
                backup_bus_id=backup_bus_id,
            )
        self.third_rail_sections = {}
        for binding in payload.get("sectionBindings", []):
            self.third_rail_sections[binding["thirdRailSectionId"]] = ThirdRailState(
                id=binding["thirdRailSectionId"],
                power_section_id=binding["powerSectionId"],
                start_meters=float(binding.get("startMeters", 0) or 0),
                end_meters=float(binding.get("endMeters", 0) or 0),
                energization_state="ENERGIZED",
                feeder_state="AVAILABLE",
                recommended_supply_mode="DOUBLE_END",
            )
        self.isolators = {
            isolator["id"]: IsolatorState(
                id=isolator["id"],
                third_rail_section_id=isolator["thirdRailSectionId"],
                state=normalized_state(isolator.get("defaultState"), "CLOSED"),
            )
            for isolator in payload.get("isolators", [])
        }
        self.stray_monitors = {
            point["id"]: StrayMonitorState(
                id=point["id"],
                section_id=point["sectionId"],
                cabinet_state="NORMAL",
                polarized_potential_volts=0.25,
                risk_level="NORMAL",
                risk_reason="bootstrapped default state",
                suggested_action="NONE",
            )
            for point in payload.get("strayCurrentMonitors", [])
        }
        self.section_loads = {
            section.power_section_id: SectionLoad(section.power_section_id)
            for section in self.third_rail_sections.values()
        }
        self.section_faults = {}
        self._solve_network()
        self._append_event("BOOTSTRAPPED", "POWER_NETWORK", self.line_id, "INFO", "power network model bootstrapped")
        return {"accepted": True, "lineId": self.line_id, "generatedAt": self.source_timestamp}

    def snapshot(self) -> dict[str, Any]:
        self._solve_network()
        return {
            "sourceTimestamp": self.source_timestamp,
            "heartbeatStatus": "UP",
            "dataQuality": "GOOD",
            "simulationRunId": self.active_run_id or "",
            "lastAcceptedTick": self.last_step_tick if self.last_step_tick is not None else -1,
            "acceptedStepCount": self.accepted_step_count,
            "topologyHash": self.topology_hash,
            "configHash": self.config_hash,
            "modelVersion": self.model_version,
            "parameterVersion": self.parameter_version,
            "bootstrapped": self.bootstrapped,
            "substations": [self._substation_payload(substation) for substation in self.substations.values()],
            "thirdRailSections": [self._third_rail_payload(section) for section in self.third_rail_sections.values()],
            "isolators": [
                {
                    "id": isolator.id,
                    "thirdRailSectionId": isolator.third_rail_section_id,
                    "state": isolator.state,
                }
                for isolator in self.isolators.values()
            ],
            "strayCurrentMonitors": [self._stray_payload(point) for point in self.stray_monitors.values()],
            "events": list(self.events[-50:]),
            "mediumVoltageBuses": [self._bus_payload(bus) for bus in self.buses.values()],
            "ringFeeders": [self._feeder_payload(feeder) for feeder in self.feeders.values()],
        }

    def _sha256(self, value: Any) -> str:
        canonical = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    def query_state(self, payload: dict[str, Any]) -> dict[str, Any]:
        # The public contract is camelCase.  Accept snake_case as well so a client-level
        # JSON naming strategy cannot silently turn a valid load update into an empty snapshot.
        incoming_loads = payload.get("sectionLoads") or payload.get("section_loads") or []
        # state/query represents one complete control-cycle load snapshot.  Clear sections
        # that are absent from this request so a train leaving a section cannot leave a stale load behind.
        self.section_loads = {
            section_id: SectionLoad(section_id)
            for section_id in self.section_loads
        }
        for load in incoming_loads:
            section_id = load.get("powerSectionId") or load.get("power_section_id") or load.get("sectionId")
            if not section_id:
                continue
            self.section_loads[section_id] = SectionLoad(
                power_section_id=section_id,
                train_ids=list(load.get("trainIds", load.get("train_ids", [])) or []),
                traction_power_watts=float(load.get("tractionPowerWatts", load.get("traction_power_watts", 0)) or 0),
                regen_power_watts=float(load.get("regenPowerWatts", load.get("regen_power_watts", 0)) or 0),
                current_amps=float(load.get("currentAmps", load.get("current_amps", 0)) or 0),
            )
        return self.snapshot()

    def step(
        self,
        simulation_run_id: str,
        tick: int,
        payload: dict[str, Any],
        train_positions: list[dict[str, Any]],
    ) -> dict[str, Any]:
        """Atomically apply T(n) fleet loads and return constraints consumed at T(n+1)."""
        if not self.bootstrapped:
            raise ValueError("POWER_BOOTSTRAP_REQUIRED")
        if not simulation_run_id:
            raise ValueError("POWER_RUN_ID_REQUIRED")
        if self.active_run_id is None:
            self.active_run_id = simulation_run_id
        elif simulation_run_id != self.active_run_id:
            if tick > 1:
                raise ValueError(
                    f"POWER_RUN_ID_MISMATCH:{simulation_run_id}!={self.active_run_id}"
                )
            self.active_run_id = simulation_run_id
            self.last_step_tick = None
            self.last_step_response = None
        if self.last_step_tick is not None:
            if tick == self.last_step_tick:
                return dict(self.last_step_response or {})
            if tick < self.last_step_tick:
                raise ValueError(f"POWER_TICK_OUT_OF_ORDER:{tick}<{self.last_step_tick}")
        snapshot = self.query_state(payload)
        response = {
            **snapshot,
            "simulationRunId": simulation_run_id,
            "tick": tick,
            "lastAcceptedTick": tick,
            "powerConstraints": self.constraints_for_positions(train_positions),
        }
        self.last_step_tick = tick
        self.last_step_response = response
        self.accepted_step_count += 1
        response["acceptedStepCount"] = self.accepted_step_count
        self.last_step_response = response
        return dict(response)

    def constraints_for_positions(self, train_positions: list[dict[str, Any]]) -> list[dict[str, Any]]:
        """Return the authoritative power constraint for every train position.

        Vehicle simulation consumes this response directly.  The central system only
        mirrors it for monitoring and does not recalculate traction capability.
        """
        if not self.bootstrapped:
            raise ValueError("POWER_BOOTSTRAP_REQUIRED")
        self._solve_network()
        sections = list(self.third_rail_sections.values())
        if not sections:
            return []
        assignments: list[tuple[dict[str, Any], ThirdRailState | None]] = []
        topology_min = min(
            (float(item.get("startMeters", 0)) for item in self.topology_segments),
            default=min(section.start_meters for section in sections),
        )
        topology_max = max(
            (float(item.get("endMeters", 0)) for item in self.topology_segments),
            default=max(section.end_meters for section in sections),
        )
        for train in train_positions:
            train_id = train.get("trainId") or train.get("train_id")
            if not train_id:
                continue
            try:
                position = float(train.get("positionMeters", train.get("position_meters", 0)))
            except (TypeError, ValueError):
                position = math.nan
            section = None
            if math.isfinite(position) and topology_min <= position < topology_max:
                section = next(
                    (
                        candidate
                        for candidate in sections
                        if candidate.start_meters <= position < candidate.end_meters
                    ),
                    None,
                )
            assignments.append((train, section))
        train_count_by_section: dict[str, int] = {}
        for _, section in assignments:
            if section is None:
                continue
            train_count_by_section[section.power_section_id] = train_count_by_section.get(section.power_section_id, 0) + 1

        constraints: list[dict[str, Any]] = []
        for train, section in assignments:
            train_id = train.get("trainId") or train.get("train_id")
            if section is None:
                constraints.append(
                    {
                        "trainId": train_id,
                        "sectionId": "UNKNOWN",
                        "railVoltage": 0.0,
                        "powerAvailableWatts": 0.0,
                        "regenPowerAvailableWatts": 0.0,
                        "energized": False,
                        "powerDeratingFactor": 0.0,
                        "currentCollectionAvailable": False,
                        "regenAvailable": False,
                        "constraintReason": "POWER_SECTION_UNKNOWN",
                    }
                )
                continue
            fault = self.section_faults.get(section.power_section_id, "")
            energized = section.energization_state == "ENERGIZED" and section.contact_rail_voltage > self.cutoff_dc_voltage
            undervoltage = energized and section.contact_rail_voltage < self.minimum_dc_voltage
            overcurrent = energized and fault == "OVERCURRENT"
            derating_factor = 0.25 if overcurrent else (0.5 if undervoltage else (1.0 if energized else 0.0))
            available_power = (
                section.contact_rail_voltage * self.max_traction_current_amps * derating_factor
                if energized
                else 0.0
            )
            regen_budget = (
                section.regen_budget_watts / max(1, train_count_by_section[section.power_section_id])
                if energized and not undervoltage
                else 0.0
            )
            reason = fault if fault else ("UNDERVOLTAGE" if undervoltage else ("NORMAL" if energized else "POWER_UNAVAILABLE"))
            constraints.append(
                {
                    "trainId": train_id,
                    "sectionId": section.power_section_id,
                    "railVoltage": round(section.contact_rail_voltage, 2),
                    "powerAvailableWatts": round(available_power, 2),
                    "regenPowerAvailableWatts": round(regen_budget, 2),
                    "energized": energized,
                    "powerDeratingFactor": derating_factor,
                    "currentCollectionAvailable": energized,
                    "regenAvailable": regen_budget > 0,
                    "constraintReason": reason,
                }
            )
        return constraints

    def topology(self) -> dict[str, Any]:
        return {
            "lineId": self.line_id,
            "lineName": self.line_name,
            "nominalDcVoltage": self.nominal_dc_voltage,
            "mediumVoltageKv": MEDIUM_VOLTAGE_KV,
            "topologySegments": list(self.topology_segments),
            "mediumVoltageBuses": [self._bus_payload(bus) for bus in self.buses.values()],
            "ringFeeders": [self._feeder_payload(feeder) for feeder in self.feeders.values()],
            "substations": [self._substation_payload(substation) for substation in self.substations.values()],
            "thirdRailSections": [self._third_rail_payload(section) for section in self.third_rail_sections.values()],
        }

    def operate(self, payload: dict[str, Any]) -> dict[str, Any]:
        target_type = payload.get("targetType", "")
        target_id = payload.get("targetId", "")
        desired_state = normalized_state(payload.get("desiredState"), "")
        trace_id = payload.get("traceId", "")
        executed = False

        if target_type == "ISOLATOR" and target_id in self.isolators:
            self.isolators[target_id].state = desired_state or "OPEN"
            executed = True
        elif target_type == "SUBSTATION" and target_id in self.substations:
            self.substations[target_id].availability = desired_state or "OUT_OF_SERVICE"
            executed = True
        elif target_type == "SUBSTATION_DEVICE":
            executed = self._operate_device(target_id, desired_state)
        elif target_type == "MEDIUM_VOLTAGE_BUS" and target_id in self.buses:
            self.buses[target_id].state = desired_state or "DEENERGIZED"
            executed = True
        elif target_type == "RING_FEEDER" and target_id in self.feeders:
            self.feeders[target_id].state = desired_state or "OPEN"
            executed = True
        elif target_type in {"STRAY_MONITOR", "STRAIN_MONITOR"} and target_id in self.stray_monitors:
            self.stray_monitors[target_id].cabinet_state = desired_state or "ABNORMAL"
            executed = True
        elif target_type == "POWER_SECTION" and self.third_rail_sections_by_power_section(target_id) is not None:
            if payload.get("operationType") == "CLEAR_FAULT" or desired_state in {"", "NORMAL", "CLEARED"}:
                self.section_faults.pop(target_id, None)
            else:
                self.section_faults[target_id] = desired_state
            executed = True

        self._solve_network()
        result_state = "EXECUTED" if executed else "REJECTED"
        detail = payload.get("reason", "operation requested")
        self._append_event(
            payload.get("operationType", "OPERATION"),
            target_type,
            target_id,
            "INFO" if executed else "WARNING",
            detail,
        )
        return {
            "accepted": True,
            "executed": executed,
            "targetId": target_id,
            "resultState": result_state,
            "reason": "" if executed else "target not found or unsupported",
            "traceId": trace_id,
            "executedAt": self.source_timestamp,
        }

    def event_list(self) -> list[dict[str, Any]]:
        return list(self.events[-50:])

    def _solve_network(self) -> None:
        self._clear_dynamic_values()
        for substation in self.substations.values():
            substation.availability = self._substation_availability(substation)
        for section in self.third_rail_sections.values():
            self._solve_third_rail_section(section)
        self._solve_medium_voltage_flows()
        self._recompute_stray_risk()
        self.source_timestamp = now_iso()

    def _solve_third_rail_section(self, section: ThirdRailState) -> None:
        load = self.section_loads.get(section.power_section_id, SectionLoad(section.power_section_id))
        substation = self._substation_for_section(section.power_section_id)
        isolators = self._isolators_for_section(section.id)
        open_isolator = any(isolator.state == "OPEN" for isolator in isolators)
        section.traction_power_watts = load.traction_power_watts
        section.regen_power_watts = load.regen_power_watts
        section.absorbed_regen_watts = min(load.traction_power_watts, load.regen_power_watts)
        section.unabsorbed_regen_watts = max(0.0, load.regen_power_watts - section.absorbed_regen_watts)
        section.regen_budget_watts = 0.0
        section.traction_current_amps = self._effective_current(load)
        fault = self.section_faults.get(section.power_section_id)
        if fault in {"DEENERGIZED", "BREAKER_TRIP", "MAINTENANCE_LOCK", "ISOLATED"}:
            section.energization_state = "DEENERGIZED"
            section.feeder_state = fault
            section.recommended_supply_mode = "OUTAGE"
            section.contact_rail_voltage = 0.0
            section.support_reason = f"injected fault: {fault}"
            return
        if fault == "UNDERVOLTAGE":
            section.energization_state = "ENERGIZED"
            section.feeder_state = "UNDERVOLTAGE"
            section.recommended_supply_mode = "DERATED"
            section.contact_rail_voltage = max(
                self.cutoff_dc_voltage + 1.0,
                self.minimum_dc_voltage - max(1.0, self.minimum_dc_voltage * 0.1),
            )
            section.support_reason = "injected fault: UNDERVOLTAGE"
            return
        if fault == "OVERCURRENT":
            section.energization_state = "ENERGIZED"
            section.feeder_state = "OVERCURRENT"
            section.recommended_supply_mode = "DERATED"
            section.contact_rail_voltage = max(self.minimum_dc_voltage, self.nominal_dc_voltage * 0.9)
            section.support_reason = "injected fault: OVERCURRENT"
            return
        if open_isolator:
            section.energization_state = "DEENERGIZED"
            section.feeder_state = "ISOLATED"
            section.recommended_supply_mode = "OUTAGE"
            section.contact_rail_voltage = 0.0
            section.support_reason = "isolator open"
            return
        if substation is None or substation.availability == "OUT_OF_SERVICE":
            support_mode = self._support_mode_from_neighbors(section)
            section.recommended_supply_mode = support_mode
            if support_mode == "OUTAGE":
                section.energization_state = "DEENERGIZED"
                section.feeder_state = "OUT_OF_SERVICE"
                section.contact_rail_voltage = 0.0
                section.support_reason = "no healthy adjacent support"
                return
            section.energization_state = "ENERGIZED"
            section.feeder_state = "BACKUP"
            section.support_reason = "adjacent substation support"
        else:
            section.energization_state = "ENERGIZED"
            section.feeder_state = "AVAILABLE"
            section.recommended_supply_mode = "DOUBLE_END"
            section.support_reason = "normal double-end supply"
        distance_km = max(0.1, (section.end_meters - section.start_meters) / 1000.0)
        resistance = distance_km * (CONTACT_RAIL_OHM_PER_KM + RUNNING_RAIL_OHM_PER_KM)
        mode_factor = {"DOUBLE_END": 0.5, "CROSS_FEED": 0.75, "SINGLE_END": 1.0}.get(section.recommended_supply_mode, 1.0)
        voltage_drop = section.traction_current_amps * resistance * mode_factor
        section.contact_rail_voltage = max(0.0, self.nominal_dc_voltage - voltage_drop)
        if section.contact_rail_voltage < self.minimum_dc_voltage:
            section.feeder_state = "UNDERVOLTAGE"
            self._append_event(
                "DC_UNDERVOLTAGE",
                "THIRD_RAIL_SECTION",
                section.id,
                "WARNING",
                f"contact rail voltage {section.contact_rail_voltage:.1f}V",
            )
        else:
            # Rectifiers are not modelled as reversible in the first version. Regeneration
            # can therefore be absorbed only by simultaneous traction in the same section.
            section.regen_budget_watts = max(0.0, section.traction_power_watts)

    def _solve_medium_voltage_flows(self) -> None:
        loads_by_bus: dict[str, float] = {bus_id: 0.0 for bus_id in self.buses}
        for substation in self.substations.values():
            load_kw = sum(
                self.third_rail_sections_by_power_section(section_id).traction_power_watts / 1000.0
                for section_id in substation.section_ids
                if self.third_rail_sections_by_power_section(section_id) is not None
            )
            substation.incoming_current_amps = load_kw / (1.732 * MEDIUM_VOLTAGE_KV) if substation.availability != "OUT_OF_SERVICE" else 0.0
            if substation.primary_bus_id in loads_by_bus:
                loads_by_bus[substation.primary_bus_id] += substation.incoming_current_amps
        for bus_id, current in loads_by_bus.items():
            bus = self.buses[bus_id]
            bus.current_amps = current if bus.state == "ENERGIZED" else 0.0
            bus.voltage_drop_percent = min(12.0, bus.current_amps / 700.0 * 5.0)
        for feeder in self.feeders.values():
            from_current = self.buses.get(feeder.from_bus_id, MediumVoltageBus(feeder.from_bus_id, "")).current_amps
            to_current = self.buses.get(feeder.to_bus_id, MediumVoltageBus(feeder.to_bus_id, "")).current_amps
            feeder.current_amps = abs(from_current - to_current) * 0.35 if feeder.state == "CLOSED" else 0.0
            if feeder.current_amps > feeder.thermal_limit_amps:
                self._append_event("MV_FEEDER_OVERLOAD", "RING_FEEDER", feeder.id, "WARNING", "medium-voltage feeder overload")
        for substation in self.substations.values():
            bus = self.buses.get(substation.primary_bus_id)
            substation.bus_voltage_drop_percent = 0.0 if bus is None else bus.voltage_drop_percent

    def _recompute_stray_risk(self) -> None:
        isolated_sections = {
            section.power_section_id
            for section in self.third_rail_sections.values()
            if section.energization_state != "ENERGIZED"
        }
        high_current_sections = {
            section.power_section_id
            for section in self.third_rail_sections.values()
            if section.traction_current_amps > 1800
        }
        for point in self.stray_monitors.values():
            load = self.section_loads.get(point.section_id, SectionLoad(point.section_id))
            if point.cabinet_state not in {"NORMAL", "AVAILABLE"} and point.section_id in high_current_sections:
                point.risk_level = "CRITICAL"
                point.risk_reason = "排流柜异常且区段回流电流偏高"
                point.suggested_action = "限制牵引负荷并安排供电检修"
                point.polarized_potential_volts = 2.6
            elif point.cabinet_state not in {"NORMAL", "AVAILABLE"}:
                point.risk_level = "WARNING"
                point.risk_reason = "排流柜状态异常"
                point.suggested_action = "检查排流柜并加强电位监测"
                point.polarized_potential_volts = 2.1
            elif point.section_id in isolated_sections:
                point.risk_level = "ATTENTION"
                point.risk_reason = "区段隔离或失电，回流路径发生变化"
                point.suggested_action = "检查回流路径和综合接地"
                point.polarized_potential_volts = 1.2
            elif load.current_amps > 1800:
                point.risk_level = "ATTENTION"
                point.risk_reason = "区段牵引回流电流偏高"
                point.suggested_action = "关注极化电位趋势"
                point.polarized_potential_volts = 1.55
            else:
                point.risk_level = "NORMAL"
                point.risk_reason = "状态正常"
                point.suggested_action = "NONE"
                point.polarized_potential_volts = 0.25

    def _operate_device(self, target_id: str, desired_state: str) -> bool:
        for substation in self.substations.values():
            for device in substation.devices:
                if device.id != target_id:
                    continue
                device.state = desired_state or device.state
                device.available = device.state not in {"OUT_OF_SERVICE", "TRIPPED", "OPEN"}
                return True
        return False

    def _substation_availability(self, substation: SubstationState) -> str:
        if substation.availability == "OUT_OF_SERVICE":
            return "OUT_OF_SERVICE"
        primary_bus = self.buses.get(substation.primary_bus_id)
        backup_bus = self.buses.get(substation.backup_bus_id)
        source_available = (primary_bus and primary_bus.state == "ENERGIZED") or (backup_bus and backup_bus.state == "ENERGIZED")
        rectifiers = [device for device in substation.devices if device.device_type == "RECTIFIER"]
        breakers = [device for device in substation.devices if device.device_type == "DC_BREAKER"]
        if not source_available:
            return "OUT_OF_SERVICE"
        if rectifiers and not any(device.available for device in rectifiers):
            return "OUT_OF_SERVICE"
        if breakers and not any(device.available for device in breakers):
            return "OUT_OF_SERVICE"
        return "AVAILABLE"

    def _support_mode_from_neighbors(self, section: ThirdRailState) -> str:
        ordered = sorted(self.third_rail_sections.values(), key=lambda item: item.start_meters)
        index = ordered.index(section)
        healthy_neighbors = 0
        for neighbor_index in (index - 1, index + 1):
            if neighbor_index < 0 or neighbor_index >= len(ordered):
                continue
            neighbor = ordered[neighbor_index]
            neighbor_substation = self._substation_for_section(neighbor.power_section_id)
            neighbor_isolated = any(isolator.state == "OPEN" for isolator in self._isolators_for_section(neighbor.id))
            if neighbor_substation is not None and neighbor_substation.availability != "OUT_OF_SERVICE" and not neighbor_isolated:
                healthy_neighbors += 1
        if healthy_neighbors >= 2:
            return "CROSS_FEED"
        if healthy_neighbors == 1:
            return "SINGLE_END"
        return "OUTAGE"

    def _effective_current(self, load: SectionLoad) -> float:
        if load.current_amps > 0:
            regen_current = load.regen_power_watts / max(1.0, self.nominal_dc_voltage)
            return max(0.0, load.current_amps - regen_current)
        net_power = max(0.0, load.traction_power_watts - load.regen_power_watts)
        return net_power / max(1.0, self.nominal_dc_voltage)

    def _substation_for_section(self, power_section_id: str) -> SubstationState | None:
        for substation in self.substations.values():
            if power_section_id in substation.section_ids:
                return substation
        return None

    def third_rail_sections_by_power_section(self, power_section_id: str) -> ThirdRailState | None:
        for section in self.third_rail_sections.values():
            if section.power_section_id == power_section_id:
                return section
        return None

    def _isolators_for_section(self, third_rail_section_id: str) -> list[IsolatorState]:
        return [
            isolator for isolator in self.isolators.values()
            if isolator.third_rail_section_id == third_rail_section_id
        ]

    def _derive_medium_voltage_network(self, payload: dict[str, Any]) -> tuple[dict[str, MediumVoltageBus], dict[str, RingFeeder]]:
        substations = payload.get("substations", [])
        bus_count = max(2, min(4, len(substations)))
        buses = {
            f"BUS-{index + 1}": MediumVoltageBus(
                id=f"BUS-{index + 1}",
                source_name=f"城市电网10kV母线{index + 1}",
            )
            for index in range(bus_count)
        }
        bus_ids = list(buses)
        feeders = {
            f"RING-{index + 1}": RingFeeder(
                id=f"RING-{index + 1}",
                from_bus_id=bus_ids[index],
                to_bus_id=bus_ids[(index + 1) % len(bus_ids)],
                length_km=1.2 + index * 0.2,
            )
            for index in range(len(bus_ids))
        }
        return buses, feeders

    def _load_beijing_reference_model(self) -> None:
        self.buses = {
            "BUS-XZM": MediumVoltageBus("BUS-XZM", "西直门方向10kV电源"),
            "BUS-DWY": MediumVoltageBus("BUS-DWY", "动物园方向10kV电源"),
        }
        self.feeders = {
            "RING-CJG-A": RingFeeder("RING-CJG-A", "BUS-XZM", "BUS-DWY", thermal_limit_amps=700.0, length_km=1.6),
            "RING-CJG-B": RingFeeder("RING-CJG-B", "BUS-DWY", "BUS-XZM", thermal_limit_amps=700.0, length_km=1.6),
        }
        self.substations = {
            "SS-CJG-W": SubstationState(
                id="SS-CJG-W",
                name="车公庄西端牵引变电所",
                supply_mode="DOUBLE_END",
                availability="AVAILABLE",
                devices=[
                    DeviceState("SS-CJG-W-TR", "西端牵引变压器", "TRACTION_TRANSFORMER", "AVAILABLE", True, ["P-CJG-W"], 2200),
                    DeviceState("SS-CJG-W-REC", "西端整流器", "RECTIFIER", "AVAILABLE", True, ["P-CJG-W"], 2200),
                    DeviceState("SS-CJG-W-DCB", "西端直流快速断路器", "DC_BREAKER", "CLOSED", True, ["P-CJG-W"], 2200),
                ],
                section_ids=["P-CJG-W"],
                primary_bus_id="BUS-XZM",
                backup_bus_id="BUS-DWY",
            ),
            "SS-CJG-E": SubstationState(
                id="SS-CJG-E",
                name="车公庄东端牵引变电所",
                supply_mode="DOUBLE_END",
                availability="AVAILABLE",
                devices=[
                    DeviceState("SS-CJG-E-TR", "东端牵引变压器", "TRACTION_TRANSFORMER", "AVAILABLE", True, ["P-CJG-E"], 2200),
                    DeviceState("SS-CJG-E-REC", "东端整流器", "RECTIFIER", "AVAILABLE", True, ["P-CJG-E"], 2200),
                    DeviceState("SS-CJG-E-DCB", "东端直流快速断路器", "DC_BREAKER", "CLOSED", True, ["P-CJG-E"], 2200),
                ],
                section_ids=["P-CJG-E"],
                primary_bus_id="BUS-DWY",
                backup_bus_id="BUS-XZM",
            ),
        }
        self.third_rail_sections = {
            "TRS-CJG-W": ThirdRailState("TRS-CJG-W", "P-CJG-W", 0, 1800, "ENERGIZED", "AVAILABLE", "DOUBLE_END"),
            "TRS-CJG-E": ThirdRailState("TRS-CJG-E", "P-CJG-E", 1800, 3600, "ENERGIZED", "AVAILABLE", "DOUBLE_END"),
        }
        self.isolators = {
            "ISO-CJG-W-A": IsolatorState("ISO-CJG-W-A", "TRS-CJG-W", "CLOSED"),
            "ISO-CJG-W-B": IsolatorState("ISO-CJG-W-B", "TRS-CJG-W", "CLOSED"),
            "ISO-CJG-E-A": IsolatorState("ISO-CJG-E-A", "TRS-CJG-E", "CLOSED"),
            "ISO-CJG-E-B": IsolatorState("ISO-CJG-E-B", "TRS-CJG-E", "CLOSED"),
        }
        self.stray_monitors = {
            "SCP-CJG-W": StrayMonitorState("SCP-CJG-W", "P-CJG-W", "NORMAL", 0.25, "NORMAL", "状态正常", "NONE"),
            "SCP-CJG-E": StrayMonitorState("SCP-CJG-E", "P-CJG-E", "NORMAL", 0.25, "NORMAL", "状态正常", "NONE"),
        }
        self.section_loads = {
            "P-CJG-W": SectionLoad("P-CJG-W"),
            "P-CJG-E": SectionLoad("P-CJG-E"),
        }

    def _clear_dynamic_values(self) -> None:
        for bus in self.buses.values():
            bus.current_amps = 0.0
            bus.voltage_drop_percent = 0.0
        for feeder in self.feeders.values():
            feeder.current_amps = 0.0
        for substation in self.substations.values():
            substation.incoming_current_amps = 0.0
            substation.bus_voltage_drop_percent = 0.0

    def _substation_payload(self, substation: SubstationState) -> dict[str, Any]:
        return {
            "id": substation.id,
            "name": substation.name,
            "supplyMode": substation.supply_mode,
            "availability": substation.availability,
            "primaryBusId": substation.primary_bus_id,
            "backupBusId": substation.backup_bus_id,
            "incomingCurrentAmps": round(substation.incoming_current_amps, 2),
            "busVoltageDropPercent": round(substation.bus_voltage_drop_percent, 2),
            "devices": [
                {
                    "id": device.id,
                    "name": device.name,
                    "deviceType": device.device_type,
                    "state": device.state,
                    "available": device.available,
                    "affectsSectionIds": device.affects_section_ids,
                    "ratedCurrentAmps": device.rated_current_amps,
                }
                for device in substation.devices
            ],
        }

    def _third_rail_payload(self, section: ThirdRailState) -> dict[str, Any]:
        return {
            "id": section.id,
            "powerSectionId": section.power_section_id,
            "startMeters": section.start_meters,
            "endMeters": section.end_meters,
            "energizationState": section.energization_state,
            "feederState": section.feeder_state,
            "recommendedSupplyMode": section.recommended_supply_mode,
            "contactRailVoltage": round(section.contact_rail_voltage, 2),
            "tractionCurrentAmps": round(section.traction_current_amps, 2),
            "tractionPowerWatts": round(section.traction_power_watts, 2),
            "regenPowerWatts": round(section.regen_power_watts, 2),
            "absorbedRegenWatts": round(section.absorbed_regen_watts, 2),
            "unabsorbedRegenWatts": round(section.unabsorbed_regen_watts, 2),
            "regenBudgetWatts": round(section.regen_budget_watts, 2),
            "supportReason": section.support_reason,
        }

    def _stray_payload(self, point: StrayMonitorState) -> dict[str, Any]:
        return {
            "id": point.id,
            "sectionId": point.section_id,
            "cabinetState": point.cabinet_state,
            "polarizedPotentialVolts": round(point.polarized_potential_volts, 3),
            "riskLevel": point.risk_level,
            "riskReason": point.risk_reason,
            "suggestedAction": point.suggested_action,
        }

    def _bus_payload(self, bus: MediumVoltageBus) -> dict[str, Any]:
        return {
            "id": bus.id,
            "sourceName": bus.source_name,
            "voltageKv": bus.voltage_kv,
            "state": bus.state,
            "currentAmps": round(bus.current_amps, 2),
            "voltageDropPercent": round(bus.voltage_drop_percent, 2),
        }

    def _feeder_payload(self, feeder: RingFeeder) -> dict[str, Any]:
        return {
            "id": feeder.id,
            "fromBusId": feeder.from_bus_id,
            "toBusId": feeder.to_bus_id,
            "state": feeder.state,
            "thermalLimitAmps": feeder.thermal_limit_amps,
            "lengthKm": feeder.length_km,
            "currentAmps": round(feeder.current_amps, 2),
        }

    def _append_event(self, event_type: str, target_type: str, target_id: str, level: str, detail: str) -> None:
        self.source_timestamp = now_iso()
        last = self.events[-1] if self.events else {}
        if (
            last.get("eventType") == event_type
            and last.get("targetType") == target_type
            and last.get("targetId") == target_id
            and last.get("detail") == detail
        ):
            return
        self.events.append(
            {
                "eventType": event_type,
                "targetType": target_type,
                "targetId": target_id,
                "level": level,
                "detail": detail,
                "occurredAt": self.source_timestamp,
            }
        )
