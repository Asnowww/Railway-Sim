package com.railwaysim.train;



import com.railwaysim.vehicle.TrainStateReport;

import com.railwaysim.vehicle.VehiclePhysicsOutput;

import java.time.Instant;



public class TrainEntity {



    private final String id;

    private final String routeId;

    private final double lengthMeters;



    private double positionMeters;

    private double speedMetersPerSecond;

    private double loadRate;

    private String status = "RUNNING";

    private String operationMode = "ATO";

    private String doorState = "CLOSED_LOCKED";

    private String tractionState = "IDLE";

    private String brakeState = "RELEASED";

    private String currentCollectionStatus = "NORMAL";

    private boolean tractionAvailable = true;

    private boolean brakeAvailable = true;

    private String selfCheckStatus = "PASS";

    private int faultLevel;

    private String availableOperationMode = "NORMAL";

    private String dataQuality = "GOOD";

    private String dynamicsState = "COASTING";

    private String dynamicsConstraintReason = "INITIAL";

    private double speedLimitMetersPerSecond;

    private double movementAuthorityDistanceMeters;

    private double stationDistanceMeters;

    private double stoppingDistanceMeters;

    private double accelerationMetersPerSecondSquared;

    private double tractionForceNewtons;

    private double brakeForceNewtons;

    private double regenBrakeForceNewtons;

    private double railCurrentAmps;

    private double tractionPowerWatts;

    private double regenPowerWatts;

    private double energyConsumedKwh;

    private double energyRegeneratedKwh;

    private String faultCode = "OK";

    private String currentStationId;

    private int dwellElapsedSeconds;

    private String lastDepartureAt;



    public TrainEntity(String id, String routeId, double positionMeters, double lengthMeters) {

        this(id, routeId, positionMeters, lengthMeters, 0.35);

    }



    public TrainEntity(String id, String routeId, double positionMeters, double lengthMeters, double loadRate) {

        this.id = id;

        this.routeId = routeId;

        this.positionMeters = positionMeters;

        this.lengthMeters = lengthMeters;

        this.loadRate = loadRate;

    }



    public void applyPhysicsOutput(VehiclePhysicsOutput output, TrainStateReport report) {

        positionMeters = Math.max(0, output.newPositionMeters());

        speedMetersPerSecond = Math.max(0, output.newSpeedMetersPerSecond());

        accelerationMetersPerSecondSquared = output.accelerationMetersPerSecondSquared();

        tractionForceNewtons = output.tractionForceNewtons();

        brakeForceNewtons = output.brakeForceNewtons();

        regenBrakeForceNewtons = output.regenBrakeForceNewtons();

        railCurrentAmps = output.railCurrentAmps();

        tractionPowerWatts = output.tractionPowerWatts();

        regenPowerWatts = output.regenPowerWatts();

        energyConsumedKwh = output.energyConsumedKwh();

        energyRegeneratedKwh = output.energyRegeneratedKwh();

        faultCode = output.faultCode();

        operationMode = report.operationMode();

        doorState = report.doorState();

        tractionState = report.tractionState();

        brakeState = report.brakeState();

        currentCollectionStatus = report.currentCollectionStatus();

        tractionAvailable = report.tractionAvailable();

        brakeAvailable = report.brakeAvailable();

        selfCheckStatus = report.selfCheckStatus();

        faultLevel = report.faultLevel();

        availableOperationMode = report.availableOperationMode();

        dataQuality = report.dataQuality();

        dynamicsState = report.dynamicsState();

        dynamicsConstraintReason = report.dynamicsConstraintReason();

        speedLimitMetersPerSecond = report.speedLimitMetersPerSecond();

        movementAuthorityDistanceMeters = report.movementAuthorityDistanceMeters();

        stationDistanceMeters = report.stationDistanceMeters();

        stoppingDistanceMeters = report.stoppingDistanceMeters();

        updateStationTracking(report);

        status = resolveStatus(report, output);

    }



    public TrainState state() {

        return new TrainState(

            id,

            routeId,

            id,

            positionMeters,

            speedMetersPerSecond,

            lengthMeters,

            positionMeters,

            Math.max(0, positionMeters - lengthMeters),

            loadRate,

            status,

            operationMode,

            speedMetersPerSecond <= 0.05,

            doorState,

            tractionState,

            brakeState,

            currentCollectionStatus,

            tractionAvailable,

            brakeAvailable,

            selfCheckStatus,

            faultLevel,

            availableOperationMode,

            dataQuality,

            dynamicsState,

            dynamicsConstraintReason,

            speedLimitMetersPerSecond,

            movementAuthorityDistanceMeters,

            stationDistanceMeters,

            stoppingDistanceMeters,

            accelerationMetersPerSecondSquared,

            tractionForceNewtons,

            brakeForceNewtons,

            regenBrakeForceNewtons,

            railCurrentAmps,

            tractionPowerWatts,

            regenPowerWatts,

            energyConsumedKwh,

            energyRegeneratedKwh,

            faultCode,

            currentStationId,

            dwellElapsedSeconds,

            lastDepartureAt

        );

    }



    private void updateStationTracking(TrainStateReport report) {

        boolean dwelling = "STATION_STOPPED".equals(report.dynamicsState()) && speedMetersPerSecond <= 0.2;

        if (dwelling) {

            if (currentStationId == null) {

                currentStationId = inferStationId();

            }

            dwellElapsedSeconds++;

            return;

        }

        if (currentStationId != null) {

            lastDepartureAt = Instant.now().toString();

            currentStationId = null;

            dwellElapsedSeconds = 0;

        }

    }



    private String inferStationId() {

        double[] stationPositions = {0, 1250, 2500, 3750, 5000};

        String[] stationIds = {"S01", "S02", "S03", "S04", "S05"};

        String nearest = null;

        double nearestDistance = Double.MAX_VALUE;

        for (int i = 0; i < stationPositions.length; i++) {

            double distance = Math.abs(positionMeters - stationPositions[i]);

            if (distance < nearestDistance) {

                nearestDistance = distance;

                nearest = stationIds[i];

            }

        }

        return nearestDistance <= 30 ? nearest : null;

    }



    private String resolveStatus(TrainStateReport report, VehiclePhysicsOutput output) {

        if (report.emergencyBrakeCommand()) {

            return "EMERGENCY_BRAKE";

        }

        if ("STATION_STOPPED".equals(report.dynamicsState()) && speedMetersPerSecond <= 0.2) {

            return "DWELLING";

        }

        if (report.faultLevel() >= 3) {

            return "FAULT";

        }

        if (report.faultLevel() > 0 || !"OK".equals(output.faultCode())) {

            return "DEGRADED";

        }

        return "RUNNING";

    }

}


