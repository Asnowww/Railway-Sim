package com.railwaysim.dispatch.disturbance;

import com.railwaysim.dispatch.config.DispatchProperties;
import com.railwaysim.dispatch.monitor.TrainRunProfile;
import com.railwaysim.dispatch.plan.CurrentRunPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DisturbanceDetector {

    private final DispatchProperties properties;
    private final Map<String, Integer> consecutiveHits = new HashMap<>();
    private final Map<String, Instant> lastTriggeredAt = new HashMap<>();
    private final Map<String, DisturbanceEvent> openEvents = new HashMap<>();

    public DisturbanceDetector(DispatchProperties properties) {
        this.properties = properties;
    }

    public List<DisturbanceEvent> detect(
        String simulationRunId,
        Instant simulatedAt,
        CurrentRunPlan plan,
        List<TrainRunProfile> profiles
    ) {
        List<DisturbanceEvent> created = new ArrayList<>();
        for (TrainRunProfile profile : profiles) {
            evaluateType(simulationRunId, simulatedAt, profile, DisturbanceType.DWELL_EXTENDED,
                profile.dwellDeviationSec() > properties.getDwellToleranceSec(), profile.dwellDeviationSec(), created);
            evaluateType(simulationRunId, simulatedAt, profile, DisturbanceType.CROWDING,
                profile.loadRate() > properties.getCrowdingLoadRate(), profile.loadRate(), created);
            evaluateType(simulationRunId, simulatedAt, profile, DisturbanceType.DEPARTURE_DELAY,
                profile.departureDelaySec() > properties.getDepartureDelaySec(), profile.departureDelaySec(), created);
            if (profile.headwayActualSec() != null) {
                double shrinkThreshold = plan.departureIntervalSec() * properties.getHeadwayShrinkRatio();
                double expandThreshold = plan.departureIntervalSec() * properties.getHeadwayExpandRatio();
                evaluateType(simulationRunId, simulatedAt, profile, DisturbanceType.HEADWAY_SHRINK,
                    profile.headwayActualSec() < shrinkThreshold, profile.headwayActualSec(), created);
                evaluateType(simulationRunId, simulatedAt, profile, DisturbanceType.HEADWAY_EXPAND,
                    profile.headwayActualSec() > expandThreshold, profile.headwayActualSec(), created);
            }
        }
        resolveRecovered(plan, profiles, simulatedAt);
        return created;
    }

    public List<DisturbanceEvent> openEvents() {
        return openEvents.values().stream()
            .filter(event -> "OPEN".equals(event.status()) || "HANDLED".equals(event.status()))
            .toList();
    }

    public List<DisturbanceEvent> events() {
        return List.copyOf(openEvents.values());
    }

    public void reset() {
        consecutiveHits.clear();
        lastTriggeredAt.clear();
        openEvents.clear();
    }

    public void attachCommand(String disturbanceId, String commandId) {
        for (Map.Entry<String, DisturbanceEvent> entry : openEvents.entrySet()) {
            DisturbanceEvent event = entry.getValue();
            if (!event.id().equals(disturbanceId)) {
                continue;
            }
            DisturbanceEvent handled = new DisturbanceEvent(
                event.id(),
                event.simulationRunId(),
                event.trainId(),
                event.stationId(),
                event.disturbanceType(),
                event.deviationValue(),
                "HANDLED",
                event.recordedAt(),
                event.resolvedAt(),
                commandId
            );
            openEvents.put(entry.getKey(), handled);
            return;
        }
    }

    private void evaluateType(
        String simulationRunId,
        Instant simulatedAt,
        TrainRunProfile profile,
        DisturbanceType type,
        boolean matched,
        double deviationValue,
        List<DisturbanceEvent> created
    ) {
        String key = profile.trainId() + ":" + type.name();
        if (!matched) {
            consecutiveHits.remove(key);
            return;
        }
        int hits = consecutiveHits.merge(key, 1, Integer::sum);
        if (hits < properties.getConfirmTicks()) {
            return;
        }
        Instant lastTriggered = lastTriggeredAt.get(key);
        if (lastTriggered != null && simulatedAt.getEpochSecond() - lastTriggered.getEpochSecond() < properties.getCooldownSec()) {
            return;
        }
        DisturbanceEvent existing = openEvents.get(key);
        if (existing != null && ("OPEN".equals(existing.status()) || "HANDLED".equals(existing.status()))) {
            return;
        }
        DisturbanceEvent event = new DisturbanceEvent(
            "DIST-" + UUID.randomUUID().toString().substring(0, 8),
            simulationRunId,
            profile.trainId(),
            profile.currentStationId(),
            type,
            deviationValue,
            "OPEN",
            simulatedAt,
            null,
            null
        );
        openEvents.put(key, event);
        lastTriggeredAt.put(key, simulatedAt);
        created.add(event);
    }

    private void resolveRecovered(CurrentRunPlan plan, List<TrainRunProfile> profiles, Instant simulatedAt) {
        Map<String, TrainRunProfile> profileByTrain = new HashMap<>();
        for (TrainRunProfile profile : profiles) {
            profileByTrain.put(profile.trainId(), profile);
        }
        for (Map.Entry<String, DisturbanceEvent> entry : new ArrayList<>(openEvents.entrySet())) {
            DisturbanceEvent event = entry.getValue();
            if (!"OPEN".equals(event.status()) && !"HANDLED".equals(event.status())) {
                continue;
            }
            TrainRunProfile profile = profileByTrain.get(event.trainId());
            if (profile == null || !isRecovered(event.disturbanceType(), profile, plan)) {
                continue;
            }
            DisturbanceEvent resolved = new DisturbanceEvent(
                event.id(),
                event.simulationRunId(),
                event.trainId(),
                event.stationId(),
                event.disturbanceType(),
                event.deviationValue(),
                "RECOVERED",
                event.recordedAt(),
                simulatedAt,
                event.commandId()
            );
            openEvents.put(entry.getKey(), resolved);
        }
    }

    private boolean isRecovered(DisturbanceType type, TrainRunProfile profile, CurrentRunPlan plan) {
        double recoverFactor = 0.3;
        return switch (type) {
            case DWELL_EXTENDED -> profile.dwellDeviationSec() <= properties.getDwellToleranceSec() * recoverFactor;
            case CROWDING -> profile.loadRate() <= properties.getCrowdingLoadRate() * recoverFactor;
            case HEADWAY_SHRINK -> profile.headwayActualSec() != null
                && profile.headwayActualSec() >= plan.departureIntervalSec() * properties.getHeadwayShrinkRatio();
            case HEADWAY_EXPAND -> profile.headwayActualSec() != null
                && profile.headwayActualSec() <= plan.departureIntervalSec() * properties.getHeadwayExpandRatio();
            case DEPARTURE_DELAY -> profile.departureDelaySec() <= properties.getDepartureDelaySec() * recoverFactor;
        };
    }
}
