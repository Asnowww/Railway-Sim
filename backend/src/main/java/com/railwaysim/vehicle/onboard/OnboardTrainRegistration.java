package com.railwaysim.vehicle.onboard;

/** @deprecated LOCAL mode only. */
@Deprecated(forRemoval=true, since="2.0")
public record OnboardTrainRegistration(
    String trainId,
    String subsystemId,
    String mode
) {
}
