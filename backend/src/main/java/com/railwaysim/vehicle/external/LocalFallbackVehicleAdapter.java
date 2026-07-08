package com.railwaysim.vehicle.external;

import com.railwaysim.vehicle.SimpleVehicleDynamicsModel;
import com.railwaysim.vehicle.VehiclePhysicsInput;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.util.List;

public class LocalFallbackVehicleAdapter implements ExternalVehicleSimulationAdapter {

    private final SimpleVehicleDynamicsModel fallbackModel;
    private volatile ExternalSimulatorHealth health = ExternalSimulatorHealth.local();

    public LocalFallbackVehicleAdapter(SimpleVehicleDynamicsModel fallbackModel) {
        this.fallbackModel = fallbackModel;
    }

    @Override
    public List<VehiclePhysicsOutput> stepFleet(List<VehiclePhysicsInput> inputs) {
        health = ExternalSimulatorHealth.local();
        return inputs.stream().map(fallbackModel::step).toList();
    }

    public List<VehiclePhysicsOutput> stepFleetWithFault(List<VehiclePhysicsInput> inputs, String faultCode) {
        return markFault(stepFleet(inputs), faultCode);
    }

    public List<VehiclePhysicsOutput> markFault(List<VehiclePhysicsOutput> outputs, String faultCode) {
        return outputs.stream()
            .map(output -> withFaultCode(output, faultCode))
            .toList();
    }

    @Override
    public ExternalSimulatorHealth health() {
        return health;
    }

    private VehiclePhysicsOutput withFaultCode(VehiclePhysicsOutput output, String faultCode) {
        return new VehiclePhysicsOutput(
            output.trainId(),
            output.newPositionMeters(),
            output.newSpeedMetersPerSecond(),
            output.accelerationMetersPerSecondSquared(),
            output.tractionForceNewtons(),
            output.brakeForceNewtons(),
            output.regenBrakeForceNewtons(),
            output.tractionPowerWatts(),
            output.railCurrentAmps(),
            output.regenPowerWatts(),
            output.energyConsumedKwh(),
            output.energyRegeneratedKwh(),
            faultCode
        );
    }
}
