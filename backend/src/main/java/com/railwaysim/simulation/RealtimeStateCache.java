package com.railwaysim.simulation;

import com.railwaysim.power.PowerSectionState;
import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class RealtimeStateCache {

    private final Map<String, VehiclePhysicsOutput> trainPhysicsState = new ConcurrentHashMap<>();
    private final Map<String, TrainStateReport> trainTcmsState = new ConcurrentHashMap<>();
    private final Map<String, TrainEnergyState> trainEnergyState = new ConcurrentHashMap<>();
    private final Map<String, PowerSectionState> powerRealtimeState = new ConcurrentHashMap<>();
    private final Map<String, VehiclePhysicsOutput> lastFmuOutput = new ConcurrentHashMap<>();

    public void updateTrainPhysics(VehiclePhysicsOutput output) {
        trainPhysicsState.put(output.trainId(), output);
        lastFmuOutput.put(output.trainId(), output);
        trainEnergyState.put(
            output.trainId(),
            new TrainEnergyState(output.trainId(), output.energyConsumedKwh(), output.energyRegeneratedKwh())
        );
    }

    public void updateTrainTcmsState(TrainStateReport report) {
        trainTcmsState.put(report.trainId(), report);
    }

    public void updatePowerSections(List<PowerSectionState> sections) {
        sections.forEach(section -> powerRealtimeState.put(section.id(), section));
    }

    public Map<String, VehiclePhysicsOutput> trainPhysicsState() {
        return Map.copyOf(trainPhysicsState);
    }

    public Map<String, TrainEnergyState> trainEnergyState() {
        return Map.copyOf(trainEnergyState);
    }

    public Map<String, TrainStateReport> trainTcmsState() {
        return Map.copyOf(trainTcmsState);
    }

    public Map<String, PowerSectionState> powerRealtimeState() {
        return Map.copyOf(powerRealtimeState);
    }

    public Map<String, VehiclePhysicsOutput> lastFmuOutput() {
        return Map.copyOf(lastFmuOutput);
    }

    public void clear() {
        trainPhysicsState.clear();
        trainTcmsState.clear();
        trainEnergyState.clear();
        powerRealtimeState.clear();
        lastFmuOutput.clear();
    }

    public record TrainEnergyState(
        String trainId,
        double energyConsumedKwh,
        double energyRegeneratedKwh
    ) {
    }
}
