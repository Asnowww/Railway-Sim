package com.railwaysim.vehicle.runtime;

import com.railwaysim.power.PowerConstraint;
import com.railwaysim.dispatch.DispatchConstraint;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.train.TrainState;
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
    String simulationRunId
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
            dispatchConstraints, powerConstraints, ""
        );
    }

    /** 无列车状态的构造器（EXTERNAL_HTTP 模式使用，9300 从本地 TrainStateHolder 读取）。 */
    public static VehicleRuntimeStepRequest withoutTrains(
        long tick, double deltaSeconds, Instant requestedAt,
        List<MovementAuthority> movementAuthorities,
        List<TrackConstraint> trackConstraints,
        List<DispatchConstraint> dispatchConstraints,
        List<PowerConstraint> powerConstraints,
        String simulationRunId
    ) {
        return new VehicleRuntimeStepRequest(
            tick, deltaSeconds, requestedAt, List.of(),
            movementAuthorities, trackConstraints, dispatchConstraints, powerConstraints,
            simulationRunId
        );
    }
}
