package com.railwaysim.vehicle.onboard;

import com.railwaysim.vehicle.VehiclePhysicsInput;
import com.railwaysim.vehicle.control.VehicleControlDecision;

/** @deprecated LOCAL mode only. */
@Deprecated(forRemoval=true, since="2.0")
public record OnboardTrainControlOutput(
    String trainId,
    String subsystemId,
    VehiclePhysicsInput physicsInput,
    String dynamicsState,
    String dynamicsConstraintReason,
    VehicleControlDecision controlDecision
) {
    public static OnboardTrainControlOutput from(String subsystemId, VehiclePhysicsInput physicsInput) {
        return from(subsystemId, physicsInput, null);
    }

    public static OnboardTrainControlOutput from(
        String subsystemId,
        VehiclePhysicsInput physicsInput,
        VehicleControlDecision controlDecision
    ) {
        return new OnboardTrainControlOutput(
            physicsInput.trainId(),
            subsystemId,
            physicsInput,
            physicsInput.dynamicsState(),
            physicsInput.dynamicsConstraintReason(),
            controlDecision
        );
    }
}
