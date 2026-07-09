package com.railwaysim.vehicle.onboard;

import com.railwaysim.vehicle.VehiclePhysicsInput;

public record OnboardTrainControlOutput(
    String trainId,
    String subsystemId,
    VehiclePhysicsInput physicsInput,
    String dynamicsState,
    String dynamicsConstraintReason
) {
    public static OnboardTrainControlOutput from(String subsystemId, VehiclePhysicsInput physicsInput) {
        return new OnboardTrainControlOutput(
            physicsInput.trainId(),
            subsystemId,
            physicsInput,
            physicsInput.dynamicsState(),
            physicsInput.dynamicsConstraintReason()
        );
    }
}
