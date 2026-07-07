package com.railwaysim.train;

import com.railwaysim.simulation.TickContext;

public class TrainEntity {

    private final String id;
    private final String routeId;
    private final double lengthMeters;
    private double positionMeters;
    private double speedMetersPerSecond;
    private double loadRate;
    private String status = "RUNNING";

    public TrainEntity(String id, String routeId, double positionMeters, double lengthMeters) {
        this.id = id;
        this.routeId = routeId;
        this.positionMeters = positionMeters;
        this.lengthMeters = lengthMeters;
    }

    public void tick(TickContext context) {
        // TODO: implement traction, braking, dwell, and movement authority constraints.
    }

    public TrainState state() {
        return new TrainState(id, routeId, positionMeters, speedMetersPerSecond, lengthMeters, loadRate, status);
    }
}

