package com.railwaysim.track;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.train.TrainState;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TrackService {

    private final List<TrackSegmentState> segments = new ArrayList<>();
    private final StaticInfrastructureCatalog infrastructureCatalog;
    private final SimulationProperties simulationProperties;

    public TrackService(
        StaticInfrastructureCatalog infrastructureCatalog,
        SimulationProperties simulationProperties
    ) {
        this.infrastructureCatalog = infrastructureCatalog;
        this.simulationProperties = simulationProperties;
    }

    @PostConstruct
    public void init() {
        reset();
    }

    public synchronized void reset() {
        OperationalLineData lineData = infrastructureCatalog.lineData();
        segments.clear();
        segments.addAll(lineData.trackSegments().stream()
            .map(segment -> new TrackSegmentState(
                segment.id(),
                segment.startMeters(),
                segment.endMeters(),
                segment.defaultSpeedLimitMetersPerSecond(),
                TrackOccupancy.FREE
            ))
            .toList());
    }

    public synchronized void updateOccupancy(List<TrainState> trains) {
        List<TrackSegmentState> updated = segments.stream()
            .map(segment -> segment.withOccupancy(isOccupied(segment, trains) ? TrackOccupancy.OCCUPIED : TrackOccupancy.FREE))
            .toList();
        segments.clear();
        segments.addAll(updated);
    }

    public synchronized List<TrackConstraint> constraintsForTrains(List<TrainState> trains) {
        OperationalLineData lineData = infrastructureCatalog.lineData();
        return trains.stream()
            .map(train -> {
                TrackSegmentState segment = segmentAt(train.positionMeters());
                double speedLimit = lineData.speedLimitAt(
                    train.positionMeters(),
                    segment.speedLimitMetersPerSecond()
                );
                double nextStationDistance = lineData.nextStationDistanceMeters(train.positionMeters());
                return new TrackConstraint(
                    train.id(),
                    segment.id(),
                    speedLimit,
                    lineData.gradientAt(train.positionMeters()),
                    simulationProperties.getDefaultCurveRadiusMeters(),
                    Double.isFinite(nextStationDistance)
                        ? nextStationDistance
                        : Math.max(0, segment.endMeters() - train.positionMeters())
                );
            })
            .toList();
    }

    public synchronized List<TrackSegmentState> states() {
        return List.copyOf(segments);
    }

    private boolean isOccupied(TrackSegmentState segment, List<TrainState> trains) {
        return trains.stream()
            .anyMatch(train -> train.positionMeters() >= segment.startMeters() && train.positionMeters() < segment.endMeters());
    }

    private TrackSegmentState segmentAt(double positionMeters) {
        return segments.stream()
            .filter(segment -> positionMeters >= segment.startMeters() && positionMeters < segment.endMeters())
            .findFirst()
            .orElseGet(() -> segments.isEmpty()
                ? new TrackSegmentState(
                    "SEG-EMPTY",
                    0,
                    infrastructureCatalog.lineData().lineLengthMeters(),
                    simulationProperties.getDefaultSpeedLimitMetersPerSecond(),
                    TrackOccupancy.FREE
                )
                : segments.get(segments.size() - 1));
    }
}
