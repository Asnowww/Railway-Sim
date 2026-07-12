package com.railwaysim.dispatch.plan;

import com.railwaysim.dispatch.config.DispatchProperties;
import com.railwaysim.dispatch.plan.DispatchPlanFile.SegmentEntry;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PlannedScheduleCalculator {

    private final DispatchProperties properties;
    private final OperationPlanLoader planLoader;

    public PlannedScheduleCalculator(DispatchProperties properties) {
        this(properties, null);
    }

    @Autowired
    public PlannedScheduleCalculator(DispatchProperties properties, OperationPlanLoader planLoader) {
        this.properties = properties;
        this.planLoader = planLoader;
    }

    public Map<String, Instant> plannedArrivals(
        String trainId,
        Instant simulationStart,
        CurrentRunPlan plan,
        List<SegmentEntry> segments,
        List<com.railwaysim.dispatch.monitor.StationInfo> stations
    ) {
        TrainServicePlan formalService = serviceFor(trainId, stations);
        if (formalService != null) {
            Map<String, Instant> formal = new HashMap<>();
            for (PlannedStop stop : formalService.stops()) {
                formal.put(stop.stationId(), simulationStart.plusSeconds(stop.arrivalOffsetSec()));
            }
            return formal;
        }
        Map<String, Instant> arrivals = new HashMap<>();
        if (stations.isEmpty()) {
            return arrivals;
        }
        int trainIndex = trainIndex(trainId);
        Instant departureFromOrigin = simulationStart.plusSeconds((long) trainIndex * plan.departureIntervalSec());
        Instant cursor = departureFromOrigin;
        for (int i = 0; i < stations.size(); i++) {
            if (i > 0) {
                cursor = cursor.plusSeconds(travelSeconds(segments, stations.get(i - 1).positionMeters(), stations.get(i).positionMeters()));
            }
            arrivals.put(stations.get(i).id(), cursor);
            cursor = cursor.plusSeconds(plan.defaultDwellTimeSec());
        }
        return arrivals;
    }

    public Map<String, Instant> plannedDepartures(
        String trainId,
        Instant simulationStart,
        CurrentRunPlan plan,
        List<SegmentEntry> segments,
        List<com.railwaysim.dispatch.monitor.StationInfo> stations
    ) {
        TrainServicePlan formalService = serviceFor(trainId, stations);
        if (formalService != null) {
            Map<String, Instant> formal = new HashMap<>();
            for (PlannedStop stop : formalService.stops()) {
                formal.put(stop.stationId(), simulationStart.plusSeconds(stop.departureOffsetSec()));
            }
            return formal;
        }
        Map<String, Instant> departures = new HashMap<>();
        Map<String, Instant> arrivals = plannedArrivals(trainId, simulationStart, plan, segments, stations);
        for (com.railwaysim.dispatch.monitor.StationInfo station : stations) {
            Instant arrival = arrivals.get(station.id());
            if (arrival != null) {
                departures.put(station.id(), arrival.plusSeconds(plan.defaultDwellTimeSec()));
            }
        }
        return departures;
    }

    private long travelSeconds(List<SegmentEntry> segments, double fromMeters, double toMeters) {
        double distance = Math.abs(toMeters - fromMeters);
        double speedLimit = segmentSpeedLimit(segments, fromMeters, toMeters);
        double plannedSpeed = speedLimit * properties.getAverageSpeedRatio();
        if (plannedSpeed <= 0) {
            return 0;
        }
        return Math.round(distance / plannedSpeed);
    }

    private double segmentSpeedLimit(List<SegmentEntry> segments, double fromMeters, double toMeters) {
        double midpoint = (fromMeters + toMeters) / 2.0;
        return segments.stream()
            .filter(segment -> midpoint >= segment.startMeters() && midpoint <= segment.endMeters())
            .mapToDouble(SegmentEntry::speedLimitMps)
            .findFirst()
            .orElse(20.0);
    }

    private int trainIndex(String trainId) {
        if (trainId == null || trainId.isBlank()) {
            return 0;
        }
        String suffix = trainId.replaceAll("\\D+", "");
        if (suffix.isBlank()) {
            return 0;
        }
        return Math.max(0, Integer.parseInt(suffix) - 1);
    }

    private TrainServicePlan serviceFor(
        String trainId,
        List<com.railwaysim.dispatch.monitor.StationInfo> stations
    ) {
        return planLoader == null || trainId == null ? null : planLoader.serviceByTrainId(trainId);
    }
}
