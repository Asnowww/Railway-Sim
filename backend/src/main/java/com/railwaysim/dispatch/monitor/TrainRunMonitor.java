package com.railwaysim.dispatch.monitor;

import com.railwaysim.dispatch.plan.CurrentRunPlan;
import com.railwaysim.dispatch.plan.OperationPlanLoader;
import com.railwaysim.dispatch.plan.PlannedScheduleCalculator;
import com.railwaysim.dispatch.plan.TrainServicePlan;
import com.railwaysim.dispatch.strategy.TrainRegulationAction;
import com.railwaysim.train.TrainState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class TrainRunMonitor {

    private final OperationPlanLoader planLoader;
    private final PlannedScheduleCalculator scheduleCalculator;
    private final StationRecordStore stationRecordStore;
    private final Map<String, String> dwellingStationByTrain = new HashMap<>();
    private final Map<String, Integer> latestDepartureDelayByTrain = new HashMap<>();
    private final Map<String, String> latestDepartureStationByTrain = new HashMap<>();
    private final Map<String, Instant> latestDepartureAtByTrain = new HashMap<>();
    private final Map<String, StationHeadwayObservation> latestStationHeadwayByTrain = new HashMap<>();
    private Instant simulationStart = Instant.now();

    public TrainRunMonitor(
        OperationPlanLoader planLoader,
        PlannedScheduleCalculator scheduleCalculator,
        StationRecordStore stationRecordStore
    ) {
        this.planLoader = planLoader;
        this.scheduleCalculator = scheduleCalculator;
        this.stationRecordStore = stationRecordStore;
    }

    public void reset(Instant simulationStart) {
        this.simulationStart = simulationStart;
        dwellingStationByTrain.clear();
        latestDepartureDelayByTrain.clear();
        latestDepartureStationByTrain.clear();
        latestDepartureAtByTrain.clear();
        latestStationHeadwayByTrain.clear();
    }

    public List<TrainRunProfile> update(
        String simulationRunId,
        Instant simulatedAt,
        CurrentRunPlan plan,
        List<TrainState> trains
    ) {
        for (TrainState train : trains) {
            detectStationEvents(simulationRunId, plan, train, simulatedAt);
        }
        rebuildStationHeadwayObservations(simulationRunId, plan);

        Map<String, List<TrainState>> trainsByOperationalGroup = new LinkedHashMap<>();
        for (TrainState train : trains) {
            trainsByOperationalGroup
                .computeIfAbsent(operationalGroupKey(train), ignored -> new ArrayList<>())
                .add(train);
        }
        List<TrainRunProfile> profiles = new ArrayList<>();
        for (List<TrainState> group : trainsByOperationalGroup.values()) {
            List<TrainState> ordered = group.stream()
                .sorted(Comparator.comparingDouble(TrainState::positionMeters).reversed())
                .toList();
            String frontTrainId = null;
            Instant frontDeparture = null;

            for (TrainState train : ordered) {
                Instant lastDeparture = latestDepartureAtByTrain.getOrDefault(
                    train.id(),
                    parseInstant(train.lastDepartureAt()).orElse(null)
                );

                StationHeadwayObservation stationObservation = latestStationHeadwayByTrain.get(train.id());
                String referenceTrainId = stationObservation == null ? frontTrainId : stationObservation.frontTrainId();
                Double headwayActual = stationObservation == null ? null : stationObservation.actualHeadwaySec();
                int headwayDeviation = stationObservation == null
                    ? 0
                    : (int) Math.round(stationObservation.headwayErrorSec());
                if (stationObservation == null && lastDeparture != null
                    && frontDeparture != null
                    && departedFromSameStation(train.id(), frontTrainId)) {
                    headwayActual = (double) (lastDeparture.getEpochSecond() - frontDeparture.getEpochSecond());
                    headwayDeviation = (int) Math.round(headwayActual - plan.departureIntervalSec());
                }

                boolean dwelling = "DWELLING".equals(train.status()) && train.speedMetersPerSecond() <= 0.5;
                int observedDwellElapsed = dwelling ? train.dwellElapsedSeconds() : 0;
                int plannedDwell = plan.defaultDwellTimeSec();
                int dwellDeviation = Math.max(0, observedDwellElapsed - plannedDwell);
                HeadwayObservation headwayObservation = classifyHeadway(referenceTrainId, headwayActual, headwayDeviation, plan);
                int departureDelay = latestDepartureDelayByTrain.getOrDefault(train.id(), 0);
                String regulationAction = referenceTrainId == null && departureDelay > 0
                    ? TrainRegulationAction.CATCH_UP
                    : headwayObservation.action();
                profiles.add(new TrainRunProfile(
                    train.id(),
                    referenceTrainId,
                    train.positionMeters(),
                    train.speedMetersPerSecond(),
                    train.loadRate(),
                    train.status(),
                    dwelling ? train.currentStationId() : null,
                    observedDwellElapsed,
                    plannedDwell,
                    dwellDeviation,
                    headwayActual,
                    headwayDeviation,
                    headwayObservation.state(),
                    regulationAction,
                    departureDelay,
                    lastDeparture
                ));
                frontTrainId = train.id();
                frontDeparture = lastDeparture;
            }
        }
        return profiles;
    }

    public List<StationHeadwayObservation> latestStationHeadways() {
        return latestStationHeadwayByTrain.values().stream()
            .sorted(Comparator.comparing(StationHeadwayObservation::departureAt))
            .toList();
    }

    public StationHeadwayObservation latestStationHeadway(String trainId) {
        return latestStationHeadwayByTrain.get(trainId);
    }

    private void rebuildStationHeadwayObservations(String simulationRunId, CurrentRunPlan plan) {
        Map<String, List<TrainStationEvent>> departuresByStationDirection = new LinkedHashMap<>();
        for (TrainStationEvent event : stationRecordStore.list(simulationRunId)) {
            if (event.eventType() != TrainStationEvent.EventType.DEPARTURE) {
                continue;
            }
            String direction = normalizedDirection(event.direction());
            departuresByStationDirection
                .computeIfAbsent(event.stationId() + "|" + direction, ignored -> new ArrayList<>())
                .add(event);
        }
        latestStationHeadwayByTrain.clear();
        int tolerance = Math.max(5, plan.departureIntervalSec() / 10);
        for (List<TrainStationEvent> stationDepartures : departuresByStationDirection.values()) {
            List<TrainStationEvent> ordered = stationDepartures.stream()
                .sorted(Comparator.comparing(TrainStationEvent::simulatedAt)
                    .thenComparing(TrainStationEvent::trainId))
                .toList();
            for (int i = 1; i < ordered.size(); i++) {
                TrainStationEvent front = ordered.get(i - 1);
                TrainStationEvent current = ordered.get(i);
                double actual = current.simulatedAt().getEpochSecond() - front.simulatedAt().getEpochSecond();
                double error = actual - plan.departureIntervalSec();
                String state = error < -tolerance ? "TOO_SHORT" : error > tolerance ? "TOO_LONG" : "ON_TARGET";
                String action = error < -tolerance
                    ? TrainRegulationAction.SLOW_DOWN
                    : error > tolerance ? TrainRegulationAction.CATCH_UP : TrainRegulationAction.NORMAL;
                StationHeadwayObservation observation = new StationHeadwayObservation(
                    current.stationId(),
                    normalizedDirection(current.direction()),
                    current.trainId(),
                    front.trainId(),
                    front.simulatedAt(),
                    current.simulatedAt(),
                    plan.departureIntervalSec(),
                    actual,
                    error,
                    state,
                    action
                );
                StationHeadwayObservation previous = latestStationHeadwayByTrain.get(current.trainId());
                if (previous == null || previous.departureAt().isBefore(observation.departureAt())) {
                    latestStationHeadwayByTrain.put(current.trainId(), observation);
                }
            }
        }
    }

    private String normalizedDirection(String direction) {
        return direction == null || direction.isBlank() ? "UNKNOWN" : direction.toUpperCase();
    }

    private String operationalGroupKey(TrainState train) {
        String routeId = train.routeId() == null || train.routeId().isBlank() ? "UNKNOWN_ROUTE" : train.routeId();
        String direction = train.direction() == null || train.direction().isBlank()
            ? "UNKNOWN_DIRECTION"
            : train.direction().toUpperCase();
        return routeId + "|" + direction;
    }

    private boolean departedFromSameStation(String trainId, String frontTrainId) {
        if (frontTrainId == null) {
            return false;
        }
        String stationId = latestDepartureStationByTrain.get(trainId);
        String frontStationId = latestDepartureStationByTrain.get(frontTrainId);
        if (stationId == null && frontStationId == null) {
            // Compatibility path for external train states that currently expose only lastDepartureAt.
            return true;
        }
        return stationId != null && stationId.equals(frontStationId);
    }

    private HeadwayObservation classifyHeadway(
        String frontTrainId,
        Double headwayActual,
        int headwayDeviation,
        CurrentRunPlan plan
    ) {
        if (frontTrainId == null) {
            return new HeadwayObservation("LEADING_TRAIN", TrainRegulationAction.NORMAL);
        }
        if (headwayActual == null) {
            return new HeadwayObservation("WAITING_DEPARTURE_DATA", TrainRegulationAction.OBSERVE);
        }
        int tolerance = Math.max(5, plan.departureIntervalSec() / 10);
        if (headwayDeviation < -tolerance) {
            return new HeadwayObservation("TOO_SHORT", TrainRegulationAction.SLOW_DOWN);
        }
        if (headwayDeviation > tolerance) {
            return new HeadwayObservation("TOO_LONG", TrainRegulationAction.CATCH_UP);
        }
        return new HeadwayObservation("ON_TARGET", TrainRegulationAction.NORMAL);
    }

    private record HeadwayObservation(String state, String action) {
    }

    private void detectStationEvents(
        String simulationRunId,
        CurrentRunPlan plan,
        TrainState train,
        Instant simulatedAt
    ) {
        TrainServicePlan service = planLoader.serviceByTrainId(train.id());
        String serviceId = service == null ? null : service.serviceId();
        String circulationId = service == null ? null : service.circulationId();
        String direction = service == null ? train.direction() : service.direction();
        String previousStation = dwellingStationByTrain.get(train.id());
        boolean dwelling = "DWELLING".equals(train.status()) && train.currentStationId() != null && !train.currentStationId().isBlank();
        if (dwelling && previousStation == null) {
            dwellingStationByTrain.put(train.id(), train.currentStationId());
            Optional<Instant> plannedArrival = Optional.ofNullable(
                scheduleCalculator.plannedArrivals(
                    train.id(),
                    simulationStart,
                    plan,
                    planLoader.segments(),
                    planLoader.stations()
                ).get(train.currentStationId())
            );
            int delay = plannedArrival
                .map(planned -> (int) (simulatedAt.getEpochSecond() - planned.getEpochSecond()))
                .orElse(0);
            stationRecordStore.append(new TrainStationEvent(
                simulationRunId,
                train.id(),
                plan.lineId(),
                train.currentStationId(),
                TrainStationEvent.EventType.ARRIVAL,
                simulatedAt,
                plannedArrival,
                delay,
                serviceId,
                circulationId,
                direction
            ));
        } else if (!dwelling && previousStation != null) {
            Optional<Instant> plannedDeparture = Optional.ofNullable(
                scheduleCalculator.plannedDepartures(
                    train.id(),
                    simulationStart,
                    plan,
                    planLoader.segments(),
                    planLoader.stations()
                ).get(previousStation)
            );
            int delay = plannedDeparture
                .map(planned -> (int) (simulatedAt.getEpochSecond() - planned.getEpochSecond()))
                .orElse(0);
            stationRecordStore.append(new TrainStationEvent(
                simulationRunId,
                train.id(),
                plan.lineId(),
                previousStation,
                TrainStationEvent.EventType.DEPARTURE,
                simulatedAt,
                plannedDeparture,
                delay,
                serviceId,
                circulationId,
                direction
            ));
            latestDepartureDelayByTrain.put(train.id(), delay);
            latestDepartureStationByTrain.put(train.id(), previousStation);
            latestDepartureAtByTrain.put(train.id(), simulatedAt);
            dwellingStationByTrain.remove(train.id());
        }
    }

    private Optional<Instant> parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Instant.parse(value));
    }
}
