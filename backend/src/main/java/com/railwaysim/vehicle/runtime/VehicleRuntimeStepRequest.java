package com.railwaysim.vehicle.runtime;

import com.railwaysim.power.PowerConstraint;
import com.railwaysim.dispatch.DispatchConstraint;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.control.DriverControlCommand;
import java.time.Instant;
import java.util.List;

public record VehicleRuntimeStepRequest(
    long tick,
    double deltaSeconds,
    Instant requestedAt,
    List<TrainState> trains,
    List<MovementAuthority> movementAuthorities,
    List<TrackConstraint> trackConstraints,
    List<DispatchConstraint> dispatchConstraints,
    List<PowerConstraint> powerConstraints,
    String simulationRunId,
    List<DriverControlCommand> driverCommands
) {
    public VehicleRuntimeStepRequest(
        long tick,
        double deltaSeconds,
        Instant requestedAt,
        List<TrainState> trains,
        List<MovementAuthority> movementAuthorities,
        List<TrackConstraint> trackConstraints,
        List<DispatchConstraint> dispatchConstraints,
        List<PowerConstraint> powerConstraints
    ) {
        this(
            tick, deltaSeconds, requestedAt, trains, movementAuthorities, trackConstraints,
            dispatchConstraints, powerConstraints, "", List.of()
        );
    }
}
