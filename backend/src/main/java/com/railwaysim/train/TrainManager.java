package com.railwaysim.train;

import com.railwaysim.dispatch.DispatchCommand;
import com.railwaysim.dispatch.config.DispatchProperties;
import com.railwaysim.dispatch.monitor.StationInfo;
import com.railwaysim.dispatch.plan.CurrentRunPlan;
import com.railwaysim.dispatch.plan.OperationPlanLoader;
import com.railwaysim.simulation.TickContext;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TrainManager {

    private final OperationPlanLoader planLoader;
    private final DispatchProperties properties;
    private final List<TrainEntity> trains = new ArrayList<>();
    private final List<DispatchCommand> stagedCommands = new ArrayList<>();
    private CurrentRunPlan currentPlan;

    public TrainManager(OperationPlanLoader planLoader, DispatchProperties properties) {
        this.planLoader = planLoader;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        reset();
    }

    public synchronized void reset() {
        trains.clear();
        stagedCommands.clear();
        currentPlan = planLoader.resolve(Instant.now());
        List<StationInfo> stations = planLoader.stations();
        double lineLength = stations.isEmpty() ? 5000 : stations.getLast().positionMeters();
        trains.add(new TrainEntity("TR-001", planLoader.lineId(), 100, 120, properties, stations, lineLength));
        trains.add(new TrainEntity("TR-002", planLoader.lineId(), 900, 120, properties, stations, lineLength));
    }

    public synchronized void stageCommands(List<DispatchCommand> commands) {
        stagedCommands.addAll(commands);
    }

    public synchronized void tickAll(TickContext context, List<DispatchCommand> drainedCommands) {
        List<DispatchCommand> commands = new ArrayList<>(drainedCommands);
        commands.addAll(stagedCommands);
        stagedCommands.clear();
        int defaultDwell = currentPlan == null ? 25 : currentPlan.defaultDwellTimeSec();
        for (TrainEntity train : trains) {
            train.applyCommands(commands);
            train.tick(context, defaultDwell);
        }
    }

    public synchronized List<TrainState> states() {
        return trains.stream().map(TrainEntity::state).toList();
    }

    public synchronized void updatePlan(CurrentRunPlan plan) {
        this.currentPlan = plan;
    }
}
