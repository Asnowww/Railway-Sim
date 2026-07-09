package com.railwaysim.vehicle.runtime;

import com.railwaysim.power.PowerConstraint;
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
    List<PowerConstraint> powerConstraints
) {
}
