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
        Instant simulatedAt,
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
                case DWELL_EXTENDED -> dwellCommand(simulationRunId, simulatedAt, target.trainId(), -5, event);
                case HEADWAY_VIOLATION -> headwayViolationCommand(simulationRunId, simulatedAt, target.trainId(), event);
                case HEADWAY_SHRINK -> dwellCommand(simulationRunId, simulatedAt, target.trainId(), 5, event);
                case HEADWAY_EXPAND -> dwellCommand(simulationRunId, simulatedAt, target.trainId(), -3, event);
                case DEPARTURE_DELAY -> dwellCommand(simulationRunId, simulatedAt, event.trainId(), -3, event);
                case CROWDING -> headwayCommand(simulationRunId, simulatedAt, plan, event);
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
                || event.disturbanceType() == DisturbanceType.HEADWAY_VIOLATION
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

    private DispatchCommand headwayViolationCommand(
        String simulationRunId,
        Instant simulatedAt,
        String trainId,
        DisturbanceEvent event
    ) {
        if ("TOO_SHORT".equals(event.headwayDirection())) {
            return dwellCommand(simulationRunId, simulatedAt, trainId, 5, event);
        }
        return dwellCommand(simulationRunId, simulatedAt, trainId, -3, event);
    }

    private DispatchCommand dwellCommand(
        String simulationRunId,
        Instant simulatedAt,
        String trainId,
        int delta,
        DisturbanceEvent event
    ) {
        String type = delta >= 0 ? "EXTEND_DWELL" : "SHORTEN_DWELL";
        Map<String, Object> payload = new HashMap<>();
        payload.put("deltaDwellSec", delta);
        payload.put("simulationRunId", simulationRunId);
        payload.put("disturbanceId", event.id());
        payload.put("executeOnNextDwell", true);
        if (event.stationId() != null && !event.stationId().isBlank()) {
            payload.put("targetStationId", event.stationId());
        }
        putHeadwayPayload(payload, event);
        return new DispatchCommand(
            "CMD-" + UUID.randomUUID().toString().substring(0, 8),
            trainId,
            type,
            payload,
            event.disturbanceType().name(),
            CommandStatus.PENDING,
            simulatedAt,
            null
        );
    }

    private DispatchCommand headwayCommand(
        String simulationRunId,
        Instant simulatedAt,
        CurrentRunPlan plan,
        DisturbanceEvent event
    ) {
        int adjusted = (int) Math.round(plan.departureIntervalSec() * 0.85);
        Map<String, Object> payload = new HashMap<>();
        payload.put("targetHeadwaySec", adjusted);
        payload.put("simulationRunId", simulationRunId);
        payload.put("disturbanceId", event.id());
        putHeadwayPayload(payload, event);
        return new DispatchCommand(
            "CMD-" + UUID.randomUUID().toString().substring(0, 8),
            event.trainId(),
            "HEADWAY_ADJUST",
            payload,
            event.disturbanceType().name(),
            CommandStatus.PENDING,
            simulatedAt,
            null
        );
    }

    private void putHeadwayPayload(Map<String, Object> payload, DisturbanceEvent event) {
        if (event.headwayDirection() != null) {
            payload.put("headwayDirection", event.headwayDirection());
        }
        if (event.actualHeadwaySec() != null) {
            payload.put("actualHeadwaySec", event.actualHeadwaySec());
        }
        if (event.targetHeadwaySec() != null) {
            payload.put("targetHeadwaySec", event.targetHeadwaySec());
        }
        if (event.toleranceSec() != null) {
            payload.put("headwayToleranceSec", event.toleranceSec());
        }
        if (event.violationSec() != null) {
            payload.put("headwayViolationSec", event.violationSec());
        }
    }
}
