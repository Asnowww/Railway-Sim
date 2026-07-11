from __future__ import annotations

from typing import TypeAlias

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictBool,
    StrictFloat,
    StrictInt,
    StrictStr,
)

from .schemas import StepFleetRequest, VehiclePhysicsInput


JsonNumber: TypeAlias = StrictInt | StrictFloat


class TrainStepPayload(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    train_id: StrictStr = Field(alias="trainId")
    lifecycle_command: StrictStr = Field(alias="lifecycleCommand")
    section_id: StrictStr = Field(alias="sectionId")
    position_meters: JsonNumber = Field(alias="positionMeters")
    speed_meters_per_second: JsonNumber = Field(alias="speedMetersPerSecond")
    train_mass_kg: JsonNumber = Field(alias="trainMassKg")
    traction_command: JsonNumber = Field(alias="tractionCommand")
    brake_command: JsonNumber = Field(alias="brakeCommand")
    emergency_brake_command: StrictBool = Field(alias="emergencyBrakeCommand")
    door_closed: StrictBool = Field(alias="doorClosed")
    gradient: JsonNumber
    curve_radius_meters: JsonNumber = Field(alias="curveRadiusMeters")
    rail_voltage: JsonNumber = Field(alias="railVoltage")
    power_available_watts: JsonNumber = Field(alias="powerAvailableWatts")
    regen_power_available_watts: JsonNumber = Field(alias="regenPowerAvailableWatts")
    current_collection_available: StrictBool = Field(alias="currentCollectionAvailable")
    adhesion_coefficient: JsonNumber = Field(alias="adhesionCoefficient")
    previous_energy_consumed_kwh: JsonNumber = Field(alias="previousEnergyConsumedKwh")
    previous_energy_regenerated_kwh: JsonNumber = Field(alias="previousEnergyRegeneratedKwh")
    speed_limit_meters_per_second: JsonNumber = Field(alias="speedLimitMetersPerSecond")
    movement_authority_distance_meters: JsonNumber = Field(alias="movementAuthorityDistanceMeters")
    station_distance_meters: JsonNumber = Field(alias="stationDistanceMeters")
    stopping_distance_meters: JsonNumber = Field(alias="stoppingDistanceMeters")
    dynamics_state: StrictStr = Field(alias="dynamicsState")
    dynamics_constraint_reason: StrictStr = Field(alias="dynamicsConstraintReason")

    def to_domain(self, step_size_seconds: float) -> VehiclePhysicsInput:
        return VehiclePhysicsInput(
            train_id=self.train_id,
            lifecycle_command=self.lifecycle_command,
            section_id=self.section_id,
            position_meters=self.position_meters,
            speed_meters_per_second=self.speed_meters_per_second,
            train_mass_kg=self.train_mass_kg,
            traction_command=self.traction_command,
            brake_command=self.brake_command,
            emergency_brake_command=self.emergency_brake_command,
            speed_limit_meters_per_second=self.speed_limit_meters_per_second,
            movement_authority_distance_meters=self.movement_authority_distance_meters,
            gradient=self.gradient,
            curve_radius_meters=self.curve_radius_meters,
            rail_voltage=self.rail_voltage,
            power_available_watts=self.power_available_watts,
            regen_power_available_watts=self.regen_power_available_watts,
            current_collection_available=self.current_collection_available,
            door_closed=self.door_closed,
            adhesion_coefficient=self.adhesion_coefficient,
            previous_energy_consumed_kwh=self.previous_energy_consumed_kwh,
            previous_energy_regenerated_kwh=self.previous_energy_regenerated_kwh,
            delta_seconds=step_size_seconds,
            dynamics_state=self.dynamics_state,
            dynamics_constraint_reason=self.dynamics_constraint_reason,
            station_distance_meters=self.station_distance_meters,
            stopping_distance_meters=self.stopping_distance_meters,
        )


class StepFleetRequestPayload(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    tick: StrictInt
    simulation_time_seconds: JsonNumber = Field(alias="simulationTimeSeconds")
    step_size_seconds: JsonNumber = Field(alias="stepSizeSeconds")
    model_version: StrictStr = Field(alias="modelVersion")
    parameter_set_id: StrictStr = Field(alias="parameterSetId")
    trace_id: StrictStr = Field(alias="traceId")
    trains: list[TrainStepPayload]

    def to_domain(self) -> StepFleetRequest:
        return StepFleetRequest(
            tick=self.tick,
            simulation_time_seconds=self.simulation_time_seconds,
            step_size_seconds=self.step_size_seconds,
            model_version=self.model_version,
            parameter_set_id=self.parameter_set_id,
            trace_id=self.trace_id,
            trains=[train.to_domain(self.step_size_seconds) for train in self.trains],
        )

    def canonical_payload(self) -> dict[str, object]:
        return self.model_dump(mode="json", by_alias=True)
