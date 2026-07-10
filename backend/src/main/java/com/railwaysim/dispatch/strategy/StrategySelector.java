package com.railwaysim.dispatch.strategy;

import com.railwaysim.dispatch.DispatchCommand;
import com.railwaysim.dispatch.command.CommandStatus;
import com.railwaysim.dispatch.disturbance.DisturbanceEvent;
import com.railwaysim.dispatch.disturbance.DisturbanceType;
import com.railwaysim.dispatch.monitor.TrainRunProfile;
import com.railwaysim.dispatch.plan.CurrentRunPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class StrategySelector {

    public List<DispatchCommand> select(
        String simulationRunId,
        List<DisturbanceEvent> events,
        List<TrainRunProfile> profiles,
        CurrentRunPlan plan
    ) {
        List<TrainRunProfile> ordered = profiles.stream()
            .sorted(Comparator.comparingDouble(TrainRunProfile::positionMeters))
            .toList();
        Map<String, TrainRunProfile> profileByTrain = new HashMap<>();
        for (TrainRunProfile profile : ordered) {
            profileByTrain.put(profile.trainId(), profile);
        }

        List<DispatchCommand> commands = new ArrayList<>();
        for (DisturbanceEvent event : events) {
            if (!"OPEN".equals(event.status())) {
                continue;
            }
            TrainRunProfile target = pickTarget(event, ordered, profileByTrain);
            if (target == null) {
                continue;
            }
            DispatchCommand command = switch (event.disturbanceType()) {
                case DWELL_EXTENDED -> dwellCommand(simulationRunId, target.trainId(), -5, event);
                case HEADWAY_SHRINK -> dwellCommand(simulationRunId, target.trainId(), 5, event);
                case HEADWAY_EXPAND -> dwellCommand(simulationRunId, target.trainId(), -3, event);
                case DEPARTURE_DELAY -> speedBiasCommand(simulationRunId, event.trainId(), 1.05, event);
                case CROWDING -> headwayCommand(simulationRunId, plan, event);
            };
            commands.add(command);
        }
        return commands;
    }

    private TrainRunProfile pickTarget(
        DisturbanceEvent event,
        List<TrainRunProfile> ordered,
        Map<String, TrainRunProfile> profileByTrain
    ) {
        if (
            event.disturbanceType() == DisturbanceType.DWELL_EXTENDED
                || event.disturbanceType() == DisturbanceType.CROWDING
                || event.disturbanceType() == DisturbanceType.DEPARTURE_DELAY
                || event.disturbanceType() == DisturbanceType.HEADWAY_SHRINK
                || event.disturbanceType() == DisturbanceType.HEADWAY_EXPAND
        ) {
            return profileByTrain.get(event.trainId());
        }
        TrainRunProfile source = profileByTrain.get(event.trainId());
        if (source == null) {
            return null;
        }
        for (TrainRunProfile profile : ordered) {
            if (profile.positionMeters() > source.positionMeters()) {
                return profile;
            }
        }
        return ordered.isEmpty() ? null : ordered.getLast();
    }

    private DispatchCommand dwellCommand(
        String simulationRunId,
        String trainId,
        int delta,
        DisturbanceEvent event
    ) {
        String type = delta >= 0 ? "EXTEND_DWELL" : "SHORTEN_DWELL";
        Map<String, Object> payload = new HashMap<>();
        payload.put("deltaDwellSec", delta);
        payload.put("simulationRunId", simulationRunId);
        payload.put("disturbanceId", event.id());
        return new DispatchCommand(
            "CMD-" + UUID.randomUUID().toString().substring(0, 8),
            trainId,
            type,
            payload,
            event.disturbanceType().name(),
            CommandStatus.PENDING,
            Instant.now(),
            null
        );
    }

    private DispatchCommand speedBiasCommand(
        String simulationRunId,
        String trainId,
        double ratio,
        DisturbanceEvent event
    ) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("speedBiasRatio", ratio);
        payload.put("simulationRunId", simulationRunId);
        payload.put("disturbanceId", event.id());
        return new DispatchCommand(
            "CMD-" + UUID.randomUUID().toString().substring(0, 8),
            trainId,
            "SPEED_BIAS",
            payload,
            event.disturbanceType().name(),
            CommandStatus.PENDING,
            Instant.now(),
            null
        );
    }

    private DispatchCommand headwayCommand(String simulationRunId, CurrentRunPlan plan, DisturbanceEvent event) {
        int adjusted = (int) Math.round(plan.departureIntervalSec() * 0.85);
        Map<String, Object> payload = new HashMap<>();
        payload.put("targetHeadwaySec", adjusted);
        payload.put("simulationRunId", simulationRunId);
        payload.put("disturbanceId", event.id());
        return new DispatchCommand(
            "CMD-" + UUID.randomUUID().toString().substring(0, 8),
            event.trainId(),
            "HEADWAY_ADJUST",
            payload,
            event.disturbanceType().name(),
            CommandStatus.PENDING,
            Instant.now(),
            null
        );
    }
}
