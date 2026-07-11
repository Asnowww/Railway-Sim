package com.railwaysim.vehicleruntime.model;

import java.time.Instant;
import java.util.List;

public record VehicleRuntimeStepRequest(
    long tick,
    double deltaSeconds,
    Instant requestedAt,
    List<TrainStateSnapshot> trains,
    List<MovementAuthoritySnapshot> movementAuthorities,
    List<TrackConstraintSnapshot> trackConstraints,
    List<DispatchConstraintSnapshot> dispatchConstraints,
    List<PowerConstraintSnapshot> powerConstraints,
    String simulationRunId,
    List<DriverControlCommandSnapshot> driverCommands
) {
    public VehicleRuntimeStepRequest(
        long tick,
        double deltaSeconds,
        Instant requestedAt,
        List<TrainStateSnapshot> trains,
        List<MovementAuthoritySnapshot> movementAuthorities,
        List<TrackConstraintSnapshot> trackConstraints,
        List<DispatchConstraintSnapshot> dispatchConstraints,
        List<PowerConstraintSnapshot> powerConstraints
    ) {
        this(
            tick, deltaSeconds, requestedAt, trains, movementAuthorities, trackConstraints,
            dispatchConstraints, powerConstraints, "", List.of()
        );
    }
}
