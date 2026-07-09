package com.railwaysim.vehicle.onboard;

import com.railwaysim.dispatch.DispatchConstraint;
import com.railwaysim.power.PowerConstraint;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.simulation.TickContext;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.train.TrainState;

public record OnboardTrainControlInput(
    TrainState train,
    TickContext context,
    MovementAuthority authority,
    TrackConstraint track,
    DispatchConstraint dispatch,
    PowerConstraint power
) {
    public OnboardTrainControlInput {
        if (train == null) {
            throw new IllegalArgumentException("train is required");
        }
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
    }
}
