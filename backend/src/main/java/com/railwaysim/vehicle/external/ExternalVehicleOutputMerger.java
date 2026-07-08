package com.railwaysim.vehicle.external;

import com.railwaysim.vehicle.VehiclePhysicsOutput;

public final class ExternalVehicleOutputMerger {

    private ExternalVehicleOutputMerger() {
    }

    public static VehiclePhysicsOutput mergeTelemetry(
        VehiclePhysicsOutput localOutput,
        ExternalTrainTelemetry telemetry
    ) {
        return new VehiclePhysicsOutput(
            localOutput.trainId(),
            Math.max(0, telemetry.cumulativeDistance()),
            Math.max(0, telemetry.speed()),
            telemetry.acceleration(),
            localOutput.tractionForceNewtons(),
            localOutput.brakeForceNewtons(),
            localOutput.regenBrakeForceNewtons(),
            localOutput.tractionPowerWatts(),
            localOutput.railCurrentAmps(),
            localOutput.regenPowerWatts(),
            localOutput.energyConsumedKwh(),
            localOutput.energyRegeneratedKwh(),
            localOutput.faultCode()
        );
    }
}
