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
    private final Map<String, Integer> consecutiveRecoveries = new HashMap<>();
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
            if (profile.headwayActualSec() != null) {
                evaluateHeadwayViolation(simulationRunId, simulatedAt, plan, profile, created);
            } else if (profile.departureDelaySec() > properties.getDepartureDelaySec()) {
                evaluateScheduleDeviation(simulationRunId, simulatedAt, plan, profile, created);
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
        consecutiveRecoveries.clear();
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
                commandId,
                event.headwayDirection(),
                event.targetHeadwaySec(),
                event.actualHeadwaySec(),
                event.toleranceSec(),
                event.violationSec()
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
        consecutiveRecoveries.remove(key);
        lastTriggeredAt.put(key, simulatedAt);
        created.add(event);
    }

    private void evaluateHeadwayViolation(
        String simulationRunId,
        Instant simulatedAt,
        CurrentRunPlan plan,
        TrainRunProfile profile,
        List<DisturbanceEvent> created
    ) {
        double actual = profile.headwayActualSec();
        double target = plan.departureIntervalSec();
        double shrinkThreshold = target * properties.getHeadwayShrinkRatio();
        double expandThreshold = target * properties.getHeadwayExpandRatio();

        if (actual < shrinkThreshold) {
            evaluateHeadwayViolation(
                simulationRunId,
                simulatedAt,
                profile,
                "TOO_SHORT",
                target,
                actual,
                target - shrinkThreshold,
                shrinkThreshold - actual,
                created
            );
            return;
        }
        if (actual > expandThreshold) {
            evaluateHeadwayViolation(
                simulationRunId,
                simulatedAt,
                profile,
                "TOO_LONG",
                target,
                actual,
                expandThreshold - target,
                actual - expandThreshold,
                created
            );
            return;
        }

        consecutiveHits.remove(headwayViolationKey(profile));
    }

    private void evaluateScheduleDeviation(
        String simulationRunId,
        Instant simulatedAt,
        CurrentRunPlan plan,
        TrainRunProfile profile,
        List<DisturbanceEvent> created
    ) {
        evaluateHeadwayViolation(
            simulationRunId,
            simulatedAt,
            profile,
            "SCHEDULE_LATE",
            plan.departureIntervalSec(),
            0,
            properties.getDepartureDelaySec(),
            profile.departureDelaySec() - properties.getDepartureDelaySec(),
            created
        );
    }

    private void evaluateHeadwayViolation(
        String simulationRunId,
        Instant simulatedAt,
        TrainRunProfile profile,
        String direction,
        double targetHeadwaySec,
        double actualHeadwaySec,
        double toleranceSec,
        double violationSec,
        List<DisturbanceEvent> created
    ) {
        String key = headwayViolationKey(profile);
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
            DisturbanceType.TRAIN_REGULATION,
            Math.max(0, violationSec),
            "OPEN",
            simulatedAt,
            null,
            null,
            direction,
            targetHeadwaySec,
            actualHeadwaySec,
            toleranceSec,
            Math.max(0, violationSec)
        );
        openEvents.put(key, event);
        consecutiveRecoveries.remove(key);
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
            if (profile == null || !isRecovered(event, profile, plan)) {
                consecutiveRecoveries.remove(entry.getKey());
                continue;
            }
            int recoveryHits = consecutiveRecoveries.merge(entry.getKey(), 1, Integer::sum);
            if (recoveryHits < Math.max(1, properties.getRecoverTicks())) {
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
                event.commandId(),
                event.headwayDirection(),
                event.targetHeadwaySec(),
                event.actualHeadwaySec(),
                event.toleranceSec(),
                event.violationSec()
            );
            openEvents.put(entry.getKey(), resolved);
            consecutiveRecoveries.remove(entry.getKey());
        }
    }

    private boolean isRecovered(DisturbanceEvent event, TrainRunProfile profile, CurrentRunPlan plan) {
        double recoverFactor = Math.max(0, properties.getRecoverRatio());
        return switch (event.disturbanceType()) {
            case DWELL_EXTENDED -> profile.dwellDeviationSec() <= properties.getDwellToleranceSec() * recoverFactor;
            case CROWDING -> profile.loadRate() <= properties.getCrowdingLoadRate() * recoverFactor;
            case TRAIN_REGULATION -> "SCHEDULE_LATE".equals(event.headwayDirection())
                ? profile.departureDelaySec() <= properties.getDepartureDelaySec() * recoverFactor
                : profile.headwayActualSec() != null
                    && profile.headwayActualSec() >= plan.departureIntervalSec() * properties.getHeadwayShrinkRatio()
                    && profile.headwayActualSec() <= plan.departureIntervalSec() * properties.getHeadwayExpandRatio();
            case HEADWAY_VIOLATION -> profile.headwayActualSec() != null
                && profile.headwayActualSec() >= plan.departureIntervalSec() * properties.getHeadwayShrinkRatio()
                && profile.headwayActualSec() <= plan.departureIntervalSec() * properties.getHeadwayExpandRatio();
            case HEADWAY_SHRINK -> profile.headwayActualSec() != null
                && profile.headwayActualSec() >= plan.departureIntervalSec() * properties.getHeadwayShrinkRatio();
            case HEADWAY_EXPAND -> profile.headwayActualSec() != null
                && profile.headwayActualSec() <= plan.departureIntervalSec() * properties.getHeadwayExpandRatio();
            case DEPARTURE_DELAY -> profile.departureDelaySec() <= properties.getDepartureDelaySec() * recoverFactor;
        };
    }

    private String headwayViolationKey(TrainRunProfile profile) {
        return profile.trainId() + ":" + DisturbanceType.TRAIN_REGULATION.name();
    }
}
