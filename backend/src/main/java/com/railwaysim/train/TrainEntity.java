package com.railwaysim.train;

import com.railwaysim.dispatch.DispatchCommand;
import com.railwaysim.dispatch.config.DispatchProperties;
import com.railwaysim.dispatch.monitor.StationInfo;
import com.railwaysim.simulation.TickContext;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TrainEntity {

    private final String id;
    private final String routeId;
    private final double lengthMeters;
    private final DispatchProperties properties;
    private final List<StationInfo> stations;
    private final double lineLengthMeters;

    private double positionMeters;
    private double speedMetersPerSecond;
    private double loadRate;
    private String status = "RUNNING";
    private String currentStationId = "";
    private int dwellElapsedSeconds;
    private String lastDepartureAt = "";
    private int dwellAdjustSec;
    private double speedBiasRatio = 1.0;
    private final Set<String> visitedStations = new HashSet<>();
    private String lastDepartedStationId = "";

    public TrainEntity(
        String id,
        String routeId,
        double positionMeters,
        double lengthMeters,
        DispatchProperties properties,
        List<StationInfo> stations,
        double lineLengthMeters
    ) {
        this.id = id;
        this.routeId = routeId;
        this.positionMeters = positionMeters;
        this.lengthMeters = lengthMeters;
        this.properties = properties;
        this.stations = stations;
        this.lineLengthMeters = lineLengthMeters;
        this.speedMetersPerSecond = properties.getBaseCruiseSpeedMps();
        this.loadRate = id.endsWith("001") ? 0.55 : 0.72;
    }

    public void applyCommands(List<DispatchCommand> commands) {
        for (DispatchCommand command : commands) {
            if (!id.equals(command.trainId())) {
                continue;
            }
            switch (command.commandType()) {
                case "EXTEND_DWELL", "SHORTEN_DWELL" -> {
                    Object delta = command.payload().get("deltaDwellSec");
                    if (delta instanceof Number number) {
                        applyDwellAdjust(number.intValue());
                    }
                }
                case "SPEED_BIAS" -> {
                    Object ratio = command.payload().get("speedBiasRatio");
                    if (ratio instanceof Number number) {
                        applySpeedBias(number.doubleValue());
                    }
                }
                default -> {
                }
            }
        }
    }

    public void applyDwellAdjust(int deltaSeconds) {
        dwellAdjustSec += deltaSeconds;
    }

    public void applySpeedBias(double ratio) {
        speedBiasRatio = ratio;
    }

    public void tick(TickContext context, int defaultDwellSec) {
        double deltaSeconds = context.deltaSeconds();
        int effectiveDwell = clampDwell(defaultDwellSec + dwellAdjustSec);
        double cruiseSpeed = properties.getBaseCruiseSpeedMps() * speedBiasRatio;

        if ("DWELLING".equals(status)) {
            speedMetersPerSecond = 0;
            dwellElapsedSeconds += (int) Math.ceil(deltaSeconds);
            if (dwellElapsedSeconds >= effectiveDwell) {
                departFromStation(cruiseSpeed);
            }
            return;
        }

        speedMetersPerSecond = cruiseSpeed;
        positionMeters += speedMetersPerSecond * deltaSeconds;
        if (positionMeters >= lineLengthMeters) {
            positionMeters = 0;
            visitedStations.clear();
            lastDepartedStationId = "";
        }

        StationInfo station = findApproachingStation();
        if (station != null && shouldStopAt(station)) {
            arriveAtStation(station);
        }
    }

    public void reset(double positionMeters) {
        this.positionMeters = positionMeters;
        this.speedMetersPerSecond = properties.getBaseCruiseSpeedMps();
        this.status = "RUNNING";
        this.currentStationId = "";
        this.dwellElapsedSeconds = 0;
        this.lastDepartureAt = "";
        this.dwellAdjustSec = 0;
        this.speedBiasRatio = 1.0;
        this.visitedStations.clear();
        this.lastDepartedStationId = "";
    }

    public TrainState state() {
        return new TrainState(
            id,
            routeId,
            positionMeters,
            speedMetersPerSecond,
            lengthMeters,
            loadRate,
            status,
            currentStationId,
            dwellElapsedSeconds,
            lastDepartureAt
        );
    }

    private void arriveAtStation(StationInfo station) {
        status = "DWELLING";
        currentStationId = station.id();
        speedMetersPerSecond = 0;
        dwellElapsedSeconds = 0;
        positionMeters = station.positionMeters();
        visitedStations.add(station.id());
    }

    private void departFromStation(double cruiseSpeed) {
        lastDepartedStationId = currentStationId;
        lastDepartureAt = Instant.now().toString();
        currentStationId = "";
        dwellElapsedSeconds = 0;
        status = "RUNNING";
        speedMetersPerSecond = cruiseSpeed;
    }

    private StationInfo findApproachingStation() {
        for (StationInfo station : stations) {
            if (Math.abs(positionMeters - station.positionMeters()) <= properties.getArrivalThresholdMeters()) {
                return station;
            }
        }
        return null;
    }

    private boolean shouldStopAt(StationInfo station) {
        if (station.id().equals(lastDepartedStationId)) {
            return false;
        }
        return !visitedStations.contains(station.id()) || station.positionMeters() >= lineLengthMeters - 1;
    }

    private int clampDwell(int dwellSec) {
        return Math.max(properties.getMinDwellSec(), Math.min(properties.getMaxDwellSec(), dwellSec));
    }
}
