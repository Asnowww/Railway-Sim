package com.railwaysim.dispatch.monitor;

import com.railwaysim.dispatch.plan.CurrentRunPlan;
import com.railwaysim.dispatch.plan.OperationPlanLoader;
import com.railwaysim.dispatch.plan.PlannedScheduleCalculator;
import com.railwaysim.train.TrainState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    }

    public List<TrainRunProfile> update(
        String simulationRunId,
        Instant simulatedAt,
        CurrentRunPlan plan,
        List<TrainState> trains
    ) {
        List<TrainState> ordered = trains.stream()
            .sorted(Comparator.comparingDouble(TrainState::positionMeters).reversed())
            .toList();
        List<TrainRunProfile> profiles = new ArrayList<>();
        String frontTrainId = null;
        Instant frontDeparture = null;

        for (TrainState train : ordered) {
            detectStationEvents(simulationRunId, plan, train, simulatedAt);
            Instant lastDeparture = parseInstant(train.lastDepartureAt()).orElse(null);

            Double headwayActual = null;
            int headwayDeviation = 0;
            if (lastDeparture != null && frontDeparture != null) {
                headwayActual = (double) (lastDeparture.getEpochSecond() - frontDeparture.getEpochSecond());
                headwayDeviation = (int) Math.round(headwayActual - plan.departureIntervalSec());
            }

            boolean dwelling = "DWELLING".equals(train.status()) && train.speedMetersPerSecond() <= 0.5;
            int observedDwellElapsed = dwelling ? train.dwellElapsedSeconds() : 0;
            int plannedDwell = plan.defaultDwellTimeSec();
            int dwellDeviation = Math.max(0, observedDwellElapsed - plannedDwell);
            HeadwayObservation headwayObservation = classifyHeadway(frontTrainId, headwayActual, headwayDeviation, plan);
            profiles.add(new TrainRunProfile(
                train.id(),
                frontTrainId,
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
                headwayObservation.action(),
                latestDepartureDelayByTrain.getOrDefault(train.id(), 0),
                lastDeparture
            ));
            frontTrainId = train.id();
            frontDeparture = lastDeparture;
        }
        return profiles;
    }

    private HeadwayObservation classifyHeadway(
        String frontTrainId,
        Double headwayActual,
        int headwayDeviation,
        CurrentRunPlan plan
    ) {
        if (frontTrainId == null) {
            return new HeadwayObservation("LEADING_TRAIN", "NONE");
        }
        if (headwayActual == null) {
            return new HeadwayObservation("WAITING_DEPARTURE_DATA", "OBSERVE");
        }
        int tolerance = Math.max(5, plan.departureIntervalSec() / 10);
        if (headwayDeviation < -tolerance) {
            return new HeadwayObservation("TOO_SHORT", "SLOW_REAR_TRAIN");
        }
        if (headwayDeviation > tolerance) {
            return new HeadwayObservation("TOO_LONG", "CATCH_UP_REAR_TRAIN");
        }
        return new HeadwayObservation("ON_TARGET", "NONE");
    }

    private record HeadwayObservation(String state, String action) {
    }

    private void detectStationEvents(
        String simulationRunId,
        CurrentRunPlan plan,
        TrainState train,
        Instant simulatedAt
    ) {
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
                delay
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
                delay
            ));
            latestDepartureDelayByTrain.put(train.id(), delay);
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
