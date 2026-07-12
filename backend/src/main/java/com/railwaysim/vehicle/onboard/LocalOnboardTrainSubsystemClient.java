package com.railwaysim.vehicle.onboard;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.config.StoppingControlProperties;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.VehiclePhysicsInput;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class LocalOnboardTrainSubsystemClient implements OnboardTrainSubsystemClient {

    private final SimulationProperties simulationProperties;
    private final StaticInfrastructureCatalog infrastructureCatalog;
    private final StoppingControlProperties stoppingProperties;
    private final Map<String, OnboardTrainSubsystem> subsystems = new ConcurrentHashMap<>();

    LocalOnboardTrainSubsystemClient(
        SimulationProperties simulationProperties,
        StaticInfrastructureCatalog infrastructureCatalog,
        StoppingControlProperties stoppingProperties
    ) {
        this.simulationProperties = simulationProperties;
        this.infrastructureCatalog = infrastructureCatalog;
        this.stoppingProperties = stoppingProperties;
    }

    LocalOnboardTrainSubsystemClient(
        SimulationProperties simulationProperties,
        StaticInfrastructureCatalog infrastructureCatalog
    ) {
        this(simulationProperties, infrastructureCatalog, new StoppingControlProperties());
    }

    @Override
    public OnboardTrainRegistration register(String trainId) {
        return subsystem(trainId).registration();
    }

    @Override
    public OnboardTrainControlOutput control(OnboardTrainControlInput input) {
        return subsystem(input.train().id()).control(input);
    }

    @Override
    public TrainStateReport buildTrainStateReport(
        TrainState train,
        VehiclePhysicsInput input,
        VehiclePhysicsOutput output
    ) {
        return subsystem(input.trainId()).buildTrainStateReport(train, input, output);
    }

    @Override
    public void remove(String trainId) {
        subsystems.remove(trainId);
    }

    @Override
    public void clear() {
        subsystems.clear();
    }

    int registeredCount() {
        return subsystems.size();
    }

    private OnboardTrainSubsystem subsystem(String trainId) {
        if (trainId == null || trainId.isBlank()) {
            throw new IllegalArgumentException("trainId is required");
        }
        return subsystems.computeIfAbsent(
            trainId,
            id -> new OnboardTrainSubsystem(
                id, simulationProperties, infrastructureCatalog, stoppingProperties)
        );
    }
}
