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
    }

    public List<TrainRunProfile> update(
        String simulationRunId,
        Instant simulatedAt,
        CurrentRunPlan plan,
        List<TrainState> trains
    ) {
        List<TrainState> ordered = trains.stream()
            .sorted(Comparator.comparingDouble(TrainState::positionMeters))
            .toList();
        Map<String, Instant> lastDepartureByTrain = new HashMap<>();
        List<TrainRunProfile> profiles = new ArrayList<>();
        Instant previousDeparture = null;

        for (TrainState train : ordered) {
            detectStationEvents(simulationRunId, plan, train, simulatedAt);
            Instant lastDeparture = parseInstant(train.lastDepartureAt()).orElse(null);
            lastDepartureByTrain.put(train.id(), lastDeparture);

            Double headwayActual = null;
            int headwayDeviation = 0;
            if (lastDeparture != null && previousDeparture != null) {
                headwayActual = (double) (lastDeparture.getEpochSecond() - previousDeparture.getEpochSecond());
                headwayDeviation = (int) Math.round(headwayActual - plan.departureIntervalSec());
            }
            if (lastDeparture != null) {
                previousDeparture = lastDeparture;
            }

            int plannedDwell = plan.defaultDwellTimeSec();
            int dwellDeviation = Math.max(0, train.dwellElapsedSeconds() - plannedDwell);
            profiles.add(new TrainRunProfile(
                train.id(),
                train.positionMeters(),
                train.speedMetersPerSecond(),
                train.loadRate(),
                train.status(),
                train.currentStationId(),
                train.dwellElapsedSeconds(),
                plannedDwell,
                dwellDeviation,
                headwayActual,
                headwayDeviation,
                lastDeparture
            ));
        }
        return profiles;
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
