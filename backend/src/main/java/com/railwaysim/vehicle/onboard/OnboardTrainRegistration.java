package com.railwaysim.vehicle.onboard;

public record OnboardTrainRegistration(
    String trainId,
    String subsystemId,
    String mode
) {
}
