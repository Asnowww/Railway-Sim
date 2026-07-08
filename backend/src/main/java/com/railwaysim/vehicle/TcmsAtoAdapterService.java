package com.railwaysim.vehicle;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.dispatch.DispatchConstraint;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.power.PowerConstraint;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.simulation.TickContext;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.train.TrainState;
import org.springframework.stereotype.Service;

@Service
public class TcmsAtoAdapterService {

    private static final double EMPTY_MASS_KG = 198_000;
    private static final double MAX_LOAD_MASS_KG = 72_000;
    private static final double DEFAULT_ADHESION = 0.9;
    private static final double GRAVITY = 9.81;
    private static final double SERVICE_BRAKE_DECELERATION = 0.9;
    private static final double STATION_STOP_WINDOW_METERS = 8.0;
    private static final double NO_STATION_DISTANCE_METERS = 1_000_000;

    private final SimulationProperties simulationProperties;
    private final StaticInfrastructureCatalog infrastructureCatalog;

    public TcmsAtoAdapterService(
        SimulationProperties simulationProperties,
        StaticInfrastructureCatalog infrastructureCatalog
    ) {
        this.simulationProperties = simulationProperties;
        this.infrastructureCatalog = infrastructureCatalog;
    }

    public VehiclePhysicsInput buildVehiclePhysicsInput(
        TrainState train,
        TickContext context,
        MovementAuthority authority,
        TrackConstraint track,
        PowerConstraint power,
        DispatchConstraint dispatch
    ) {
        double speedLimit = applyDispatchConstraint(resolveSpeedLimit(authority, track), dispatch);
        double maDistance = resolveMovementAuthorityDistance(train, authority);
        boolean doorClosed = "CLOSED_LOCKED".equals(train.doorState());
        double stationDistance = resolveStationDistance(train, track);
        DynamicsDecision decision = decideDynamicsState(
            train,
            speedLimit,
            maDistance,
            stationDistance,
            doorClosed,
            track,
            power,
            dispatch,
            authority
        );

        return new VehiclePhysicsInput(
            train.id(),
            train.positionMeters(),
            train.speedMetersPerSecond(),
            EMPTY_MASS_KG + MAX_LOAD_MASS_KG * clamp(train.loadRate(), 0, 1),
            decision.tractionCommand(),
            decision.brakeCommand(),
            decision.emergencyBrake(),
            speedLimit,
            maDistance,
            track == null ? 0 : track.gradient(),
            track == null ? 1_000 : track.curveRadiusMeters(),
            power == null ? 1500 : power.railVoltage(),
            power == null ? 3_200_000 : power.powerAvailableWatts(),
            doorClosed,
            DEFAULT_ADHESION,
            train.energyConsumedKwh(),
            train.energyRegeneratedKwh(),
            context.deltaSeconds(),
            decision.state().name(),
            decision.reason(),
            stationDistance,
            decision.stoppingDistanceMeters()
        );
    }

    public TrainStateReport buildTrainStateReport(VehiclePhysicsInput input, VehiclePhysicsOutput output) {
        return buildTrainStateReport(input, output, null);
    }

    public TrainStateReport buildTrainStateReport(
        VehiclePhysicsInput input,
        VehiclePhysicsOutput output,
        DispatchConstraint dispatch
    ) {
        return new TrainStateReport(
            input.trainId(),
            resolveOperationMode(input, dispatch),
            input.doorClosed(),
            input.doorClosed() ? "CLOSED_LOCKED" : "OPEN",
            resolveTractionState(input, output),
            resolveBrakeState(input, output),
            resolveCurrentCollectionStatus(input, output),
            input.doorClosed() && input.powerAvailableWatts() > 0 && input.railVoltage() > 0,
            !"BRAKE_UNAVAILABLE".equals(output.faultCode()),
            resolveSelfCheckStatus(input, output),
            resolveFaultLevel(input, output),
            resolveAvailableOperationMode(input, output),
            resolveDataQuality(output),
            input.dynamicsState(),
            input.dynamicsConstraintReason(),
            input.speedLimitMetersPerSecond(),
            input.movementAuthorityDistanceMeters(),
            input.stationDistanceMeters(),
            input.stoppingDistanceMeters(),
            input.tractionCommand(),
            input.brakeCommand(),
            input.emergencyBrakeCommand(),
            input.railVoltage(),
            input.powerAvailableWatts(),
            output.faultCode()
        );
    }

    private DynamicsDecision decideDynamicsState(
        TrainState train,
        double speedLimit,
        double maDistance,
        double stationDistance,
        boolean doorClosed,
        TrackConstraint track,
        PowerConstraint power,
        DispatchConstraint dispatch,
        MovementAuthority authority
    ) {
        double speed = train.speedMetersPerSecond();
        double stoppingDistance = stoppingDistanceMeters(speed, track == null ? 0 : track.gradient());

        if (!doorClosed || !train.brakeAvailable() || "FAIL".equals(train.selfCheckStatus())) {
            return brakeDecision(
                TrainDynamicsState.SELF_CHECK_BLOCKED,
                !doorClosed ? "DOOR_NOT_LOCKED" : "SELF_CHECK_FAILED",
                speed,
                stoppingDistance,
                false
            );
        }
        if (authority != null && maDistance <= 0) {
            return brakeDecision(
                TrainDynamicsState.SAFETY_BRAKE,
                "MOVEMENT_AUTHORITY_EXHAUSTED",
                speed,
                stoppingDistance,
                true
            );
        }
        if (dispatch != null && dispatch.holdTrain()) {
            return new DynamicsDecision(
                TrainDynamicsState.DISPATCH_HOLD,
                "DISPATCH_HOLD",
                0,
                speed > 0.1 ? 1 : 0.6,
                false,
                stoppingDistance
            );
        }
        if (power != null && (!power.currentCollectionAvailable() || power.powerAvailableWatts() <= 0)) {
            return brakeDecision(
                TrainDynamicsState.POWER_LOSS,
                power.constraintReason(),
                speed,
                stoppingDistance,
                false
            );
        }
        if (!train.tractionAvailable()) {
            return new DynamicsDecision(
                TrainDynamicsState.SELF_CHECK_BLOCKED,
                "TRACTION_UNAVAILABLE",
                0,
                speed > 0.1 ? 0.4 : 0,
                false,
                stoppingDistance
            );
        }

        double maBrakeTrigger = stoppingDistance + simulationProperties.getSafetyGapMeters() * 0.5;
        if (maDistance <= maBrakeTrigger) {
            return new DynamicsDecision(
                TrainDynamicsState.MA_BRAKE,
                "MA_DISTANCE_LIMIT",
                0,
                brakeForDistance(maDistance, stoppingDistance, simulationProperties.getSafetyGapMeters() * 0.5),
                false,
                stoppingDistance
            );
        }

        if (stationDistance <= STATION_STOP_WINDOW_METERS && speed <= 0.2) {
            return new DynamicsDecision(
                TrainDynamicsState.STATION_STOPPED,
                "STATION_STOP_WINDOW",
                0,
                0.6,
                false,
                stoppingDistance
            );
        }

        double stationBrakeBuffer = stationApproachBufferMeters(speed);
        if (stationDistance <= stoppingDistance + stationBrakeBuffer) {
            return new DynamicsDecision(
                TrainDynamicsState.STATION_BRAKE,
                "STATION_APPROACH",
                0,
                brakeForDistance(stationDistance, stoppingDistance, stationBrakeBuffer),
                false,
                stoppingDistance
            );
        }

        double overspeed = speed - speedLimit;
        if (overspeed > 0) {
            return new DynamicsDecision(
                TrainDynamicsState.OVERSPEED_BRAKE,
                "SPEED_LIMIT_EXCEEDED",
                0,
                clamp(overspeed / 3.0, 0.2, 1),
                false,
                stoppingDistance
            );
        }

        double speedMargin = speedLimit - speed;
        double tractionCommand = tractionForSpeedMargin(speedMargin, speedLimit);
        if (power != null && power.powerDeratingFactor() < 0.95 && tractionCommand > 0) {
            return new DynamicsDecision(
                TrainDynamicsState.POWER_DERATED,
                power.constraintReason(),
                tractionCommand * clamp(power.powerDeratingFactor(), 0, 1),
                0,
                false,
                stoppingDistance
            );
        }
        if (speedMargin > Math.max(1.5, speedLimit * 0.08)) {
            return new DynamicsDecision(
                TrainDynamicsState.ACCELERATING,
                "SPEED_MARGIN_AVAILABLE",
                tractionCommand,
                0,
                false,
                stoppingDistance
            );
        }
        if (speedMargin > 0.4) {
            return new DynamicsDecision(
                TrainDynamicsState.CRUISING,
                "NEAR_TARGET_SPEED",
                Math.min(tractionCommand, 0.25),
                0,
                false,
                stoppingDistance
            );
        }
        return new DynamicsDecision(
            TrainDynamicsState.COASTING,
            "TARGET_SPEED_REACHED",
            0,
            0,
            false,
            stoppingDistance
        );
    }

    private double resolveSpeedLimit(MovementAuthority authority, TrackConstraint track) {
        double authorityLimit = authority == null
            ? simulationProperties.getDefaultSpeedLimitMetersPerSecond()
            : authority.speedLimitMetersPerSecond();
        double trackLimit = track == null
            ? simulationProperties.getDefaultSpeedLimitMetersPerSecond()
            : track.speedLimitMetersPerSecond();
        return Math.max(0, Math.min(authorityLimit, trackLimit));
    }

    private double resolveMovementAuthorityDistance(TrainState train, MovementAuthority authority) {
        if (authority == null) {
            double lineLengthMeters = infrastructureCatalog.lineData().lineLengthMeters() > 0
                ? infrastructureCatalog.lineData().lineLengthMeters()
                : simulationProperties.getDefaultLineLengthMeters();
            return lineLengthMeters - train.positionMeters();
        }
        return Math.max(0, authority.authorityEndMeters() - train.positionMeters());
    }

    private double resolveStationDistance(TrainState train, TrackConstraint track) {
        double distance = track == null
            ? infrastructureCatalog.lineData().nextStationDistanceMeters(train.positionMeters())
            : track.stationDistanceMeters();
        if (!Double.isFinite(distance)) {
            return NO_STATION_DISTANCE_METERS;
        }
        return Math.max(0, distance);
    }

    private double applyDispatchConstraint(double speedLimit, DispatchConstraint dispatch) {
        if (dispatch == null) {
            return speedLimit;
        }
        return dispatch.applyToSpeedLimit(speedLimit);
    }

    private String resolveOperationMode(VehiclePhysicsInput input, DispatchConstraint dispatch) {
        if (input.emergencyBrakeCommand()) {
            return "ATP_BRAKE";
        }
        if ("STATION_BRAKE".equals(input.dynamicsState()) || "STATION_STOPPED".equals(input.dynamicsState())) {
            return "STATION_CONTROL";
        }
        if (dispatch != null && dispatch.holdTrain()) {
            return "DISPATCH_HOLD";
        }
        if ("POWER_DERATED".equals(input.dynamicsState())) {
            return "DEGRADED";
        }
        if (dispatch != null && !"NORMAL".equals(dispatch.reason())) {
            return "DISPATCH_ADJUST";
        }
        return "ATO";
    }

    private String resolveTractionState(VehiclePhysicsInput input, VehiclePhysicsOutput output) {
        if (input.tractionCommand() <= 0 || output.tractionForceNewtons() <= 0) {
            return "IDLE";
        }
        if ("POWER_DERATED".equals(input.dynamicsState()) || input.powerAvailableWatts() < 3_200_000) {
            return "DERATED";
        }
        return "APPLYING";
    }

    private String resolveBrakeState(VehiclePhysicsInput input, VehiclePhysicsOutput output) {
        if (input.emergencyBrakeCommand()) {
            return "EMERGENCY";
        }
        if (output.regenBrakeForceNewtons() > 0) {
            return "REGENERATIVE";
        }
        if (output.brakeForceNewtons() > 0) {
            return "SERVICE";
        }
        return "RELEASED";
    }

    private String resolveCurrentCollectionStatus(VehiclePhysicsInput input, VehiclePhysicsOutput output) {
        if ("CURRENT_COLLECTION_LOST".equals(output.faultCode()) || input.railVoltage() <= 0 || input.powerAvailableWatts() <= 0) {
            return "LOST";
        }
        if ("LOW_VOLTAGE".equals(output.faultCode()) || input.railVoltage() < 1000) {
            return "LOW_VOLTAGE";
        }
        return "NORMAL";
    }

    private String resolveSelfCheckStatus(VehiclePhysicsInput input, VehiclePhysicsOutput output) {
        if (
            !input.doorClosed()
                || "CURRENT_COLLECTION_LOST".equals(output.faultCode())
                || input.railVoltage() <= 0
                || input.powerAvailableWatts() <= 0
        ) {
            return "FAIL";
        }
        if (!"OK".equals(output.faultCode())) {
            return "WARN";
        }
        return "PASS";
    }

    private int resolveFaultLevel(VehiclePhysicsInput input, VehiclePhysicsOutput output) {
        if (input.railVoltage() <= 0 || input.powerAvailableWatts() <= 0) {
            return 3;
        }
        return switch (output.faultCode()) {
            case "OK" -> input.emergencyBrakeCommand() ? 3 : 0;
            case "LOW_VOLTAGE", "TRACTION_UNAVAILABLE", "FMU_STEP_FAILED", "EXTERNAL_SIM_FALLBACK" -> 2;
            case "CURRENT_COLLECTION_LOST", "BRAKE_UNAVAILABLE", "DOOR_NOT_LOCKED", "ATP_BRAKE" -> 3;
            default -> 2;
        };
    }

    private String resolveAvailableOperationMode(VehiclePhysicsInput input, VehiclePhysicsOutput output) {
        int faultLevel = resolveFaultLevel(input, output);
        if (faultLevel >= 3) {
            return "NO_DEPARTURE";
        }
        if (faultLevel > 0) {
            return "DEGRADED";
        }
        return "NORMAL";
    }

    private String resolveDataQuality(VehiclePhysicsOutput output) {
        if ("FMU_STEP_FAILED".equals(output.faultCode()) || "EXTERNAL_SIM_FALLBACK".equals(output.faultCode())) {
            return "FALLBACK";
        }
        return "OK".equals(output.faultCode()) ? "GOOD" : "INVALID";
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private DynamicsDecision brakeDecision(
        TrainDynamicsState state,
        String reason,
        double speed,
        double stoppingDistance,
        boolean emergencyBrake
    ) {
        return new DynamicsDecision(
            state,
            reason,
            0,
            emergencyBrake ? 1 : speed > 0.1 ? 0.8 : 0.6,
            emergencyBrake,
            stoppingDistance
        );
    }

    private double tractionForSpeedMargin(double speedMargin, double speedLimit) {
        if (speedMargin <= 0.4) {
            return 0;
        }
        return clamp(speedMargin / Math.max(3.0, speedLimit * 0.25), 0, 1);
    }

    private double brakeForDistance(double remainingDistance, double stoppingDistance, double bufferMeters) {
        double shortfall = stoppingDistance + bufferMeters - remainingDistance;
        return clamp(shortfall / Math.max(bufferMeters, 1), 0.2, 1);
    }

    private double stoppingDistanceMeters(double speedMetersPerSecond, double gradient) {
        // The XLS can contain steep local grade records; clamp only the planning contribution.
        double planningGradient = clamp(gradient, -0.04, 0.04);
        double effectiveDeceleration = clamp(
            SERVICE_BRAKE_DECELERATION + planningGradient * GRAVITY,
            0.35,
            1.25
        );
        return speedMetersPerSecond * speedMetersPerSecond / (2 * effectiveDeceleration);
    }

    private double stationApproachBufferMeters(double speedMetersPerSecond) {
        return clamp(Math.max(30, speedMetersPerSecond * 6), 30, 140);
    }

    private record DynamicsDecision(
        TrainDynamicsState state,
        String reason,
        double tractionCommand,
        double brakeCommand,
        boolean emergencyBrake,
        double stoppingDistanceMeters
    ) {
    }
}
