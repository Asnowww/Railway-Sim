package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.config.StoppingControlProperties;
import com.railwaysim.vehicleruntime.config.VehicleParameters;
import com.railwaysim.vehicleruntime.model.DispatchConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.DriverControlCommandSnapshot;
import com.railwaysim.vehicleruntime.model.MovementAuthoritySnapshot;
import com.railwaysim.vehicleruntime.model.PowerConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.TrackConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsInputDto;

/**
 * 控制队列负责把中央约束转换为单车牵引/制动输入。
 * 从 TrainStateHolder 读取列车状态（不再接收 TrainStateSnapshot）。
 */
final class VehicleControlQueue {

    private static final double DEFAULT_ADHESION = 0.9;
    private static final double GRAVITY = 9.81;
    private static final double NO_STATION_DISTANCE_METERS = 1_000_000;
    private static final double DEPARTURE_WINDOW_METERS = 40.0;

    private final VehicleRuntimeQueue queue;
    private final VehicleRuntimeProperties properties;
    private final VehicleLoadPolicy loadPolicy;
    private final VehicleParameters vehicleParameters;
    private final DriverCommandHolder driverCommandHolder;
    private final StoppingControlProperties stoppingProperties;

    /** 离站释放时的列车位置(m) */
    private double departureOriginMeters = -1;

    VehicleControlQueue(
        VehicleRuntimeProperties properties,
        VehicleLoadPolicy loadPolicy,
        VehicleParameters vehicleParameters,
        DriverCommandHolder driverCommandHolder,
        StoppingControlProperties stoppingProperties
    ) {
        this.queue = new VehicleRuntimeQueue(properties.getQueueCapacity());
        this.properties = properties;
        this.loadPolicy = loadPolicy;
        this.vehicleParameters = vehicleParameters;
        this.driverCommandHolder = driverCommandHolder;
        this.stoppingProperties = stoppingProperties;
    }

    VehiclePhysicsInputDto control(
        long tick, double deltaSeconds,
        TrainStateHolder state,
        MovementAuthoritySnapshot authority,
        TrackConstraintSnapshot track,
        DispatchConstraintSnapshot dispatch,
        PowerConstraintSnapshot power
    ) {
        return queue.execute(tick, () -> buildInput(deltaSeconds, state, authority, track, dispatch, power));
    }

    DriverControlCommandSnapshot latestDriverCommand(String trainId) {
        return driverCommandHolder.latest(trainId);
    }

    private VehiclePhysicsInputDto buildInput(
        double deltaSeconds, TrainStateHolder state,
        MovementAuthoritySnapshot authority, TrackConstraintSnapshot track,
        DispatchConstraintSnapshot dispatch, PowerConstraintSnapshot power
    ) {
        double speedLimit = applyDispatchSpeed(resolveSpeedLimit(authority, track), dispatch);
        double maDistance = resolveMovementAuthorityDistance(state, authority);
        boolean doorClosed = "CLOSED_LOCKED".equals(state.getDoorState());
        double stationDistance = resolveStationDistance(track);
        boolean departing = updateDepartureWindow(state, dispatch);
        if (departing) {
            stationDistance = NO_STATION_DISTANCE_METERS;
        }
        double loadMassKg = loadPolicy.loadMassKg(state.getLoadMassKg(), state.getLoadRate());
        DynamicsDecision decision = decideDynamicsState(
            state,
            speedLimit,
            maDistance,
            stationDistance,
            doorClosed,
            track,
            power,
            departing
        );

        return new VehiclePhysicsInputDto(
            state.trainId(),
            "STEP",
            power == null ? "" : nullTo(power.sectionId(), ""),
            state.getPositionMeters(),
            state.getSpeedMetersPerSecond(),
            loadPolicy.totalMassKg(loadMassKg),
            decision.tractionCommand(),
            decision.brakeCommand(),
            decision.emergencyBrake(),
            speedLimit,
            maDistance,
            track == null ? 0 : track.gradient(),
            track == null ? 1_000 : track.curveRadiusMeters(),
            power == null ? vehicleParameters.power().nominalVoltage() : power.railVoltage(),
            power == null ? vehicleParameters.fullMechanicalPowerGridDemandWatts() : power.powerAvailableWatts(),
            power == null || power.regenPowerAvailableWatts() == null
                ? 0
                : Math.max(0, power.regenPowerAvailableWatts()),
            power == null || power.currentCollectionAvailable(),
            doorClosed,
            DEFAULT_ADHESION,
            state.getEnergyConsumedKwh(),
            state.getEnergyRegeneratedKwh(),
            Math.max(deltaSeconds, 0.001),
            decision.state().name(),
            decision.reason(),
            stationDistance,
            decision.stoppingDistanceMeters()
        );
    }

    private DynamicsDecision decideDynamicsState(
        TrainStateHolder state,
        double speedLimit,
        double maDistance,
        double stationDistance,
        boolean doorClosed,
        TrackConstraintSnapshot track,
        PowerConstraintSnapshot power,
        boolean departing
    ) {
        DriverControlCommandSnapshot driverCommand = driverCommandHolder.latest(state.trainId());
        double speed = state.getSpeedMetersPerSecond();
        double loadMassKg = loadPolicy.loadMassKg(state.getLoadMassKg(), state.getLoadRate());
        double brakingFactor = loadPolicy.brakingDecelerationFactor(loadMassKg, state.getAvailableBrakeCount());
        double stoppingDistance = stoppingDistanceMeters(speed, track == null ? 0 : track.gradient(), brakingFactor);
        double tractionCapacityFactor = loadPolicy.tractionCommandFactor(loadMassKg, state.getAvailableTractionCount());

        if (driverCommand != null && driverCommand.emergencyBrake()) {
            return brakeDecision(
                TrainDynamicsState.SAFETY_BRAKE,
                "DRIVER_CAB_EMERGENCY_BRAKE",
                speed,
                stoppingDistance,
                true
            );
        }

        if (!"IN_SERVICE".equals(state.getControlSessionState())) {
            return brakeDecision(TrainDynamicsState.SELF_CHECK_BLOCKED, "CONTROL_SESSION_" + state.getControlSessionState(), speed, stoppingDistance, false);
        }
        if (!doorClosed || !state.isBrakeAvailable() || state.getAvailableBrakeCount() <= 0 || "FAIL".equals(state.getSelfCheckStatus())) {
            return brakeDecision(TrainDynamicsState.SELF_CHECK_BLOCKED, resolveSelfCheckBlockReason(doorClosed, state), speed, stoppingDistance, false);
        }
        if (maDistance <= 0) {
            return brakeDecision(TrainDynamicsState.SAFETY_BRAKE, "MOVEMENT_AUTHORITY_EXHAUSTED", speed, stoppingDistance, true);
        }
        if (power != null && (!power.currentCollectionAvailable() || power.powerAvailableWatts() <= 0)) {
            return brakeDecision(TrainDynamicsState.POWER_LOSS, nullTo(power.constraintReason(), "POWER_UNAVAILABLE"), speed, stoppingDistance, false);
        }
        if (!state.isTractionAvailable() || state.getAvailableTractionCount() <= 0) {
            return new DynamicsDecision(TrainDynamicsState.SELF_CHECK_BLOCKED, "TRACTION_UNAVAILABLE", 0, speed > 0.1 ? 0.4 : 0, false, stoppingDistance);
        }

        double maBrakeTrigger = stoppingDistance + properties.getSafetyGapMeters() * 0.5;
        if (maDistance <= maBrakeTrigger) {
            return new DynamicsDecision(
                TrainDynamicsState.MA_BRAKE,
                "MA_DISTANCE_LIMIT",
                0,
                brakeForDistance(maDistance, stoppingDistance, properties.getSafetyGapMeters() * 0.5),
                false,
                stoppingDistance
            );
        }
        if (stationDistance <= stoppingProperties.getStationStopWindowMeters()
            && speed <= stoppingProperties.getZeroSpeedMetersPerSecond()) {
            return new DynamicsDecision(TrainDynamicsState.STATION_STOPPED, "STATION_STOP_WINDOW", 0, 0.6, false, stoppingDistance);
        }
        double stationBrakeBuffer = stationApproachBufferMeters(speed);
        if (stationDistance <= stoppingDistance + stationBrakeBuffer) {
            return new DynamicsDecision(TrainDynamicsState.STATION_BRAKE, "STATION_APPROACH", 0, brakeForDistance(stationDistance, stoppingDistance, stationBrakeBuffer), false, stoppingDistance);
        }
        double overspeed = speed - speedLimit;
        if (overspeed > 0) {
            return new DynamicsDecision(TrainDynamicsState.OVERSPEED_BRAKE, "SPEED_LIMIT_EXCEEDED", 0, clamp(overspeed / 3.0, 0.2, 1), false, stoppingDistance);
        }

        boolean manualCommand = driverCommand != null
            && "MANUAL".equalsIgnoreCase(driverCommand.operationMode());
        if (manualCommand) {
            if (driverCommand.expired(java.time.Instant.now())) {
                return new DynamicsDecision(
                    TrainDynamicsState.SAFETY_BRAKE,
                    "DRIVER_COMMAND_STALE",
                    0,
                    speed > 0.1 ? 0.5 : 0.6,
                    false,
                    stoppingDistance
                );
            }
            double manualBrake = clamp(driverCommand.brakeCommand(), 0, 1);
            double manualTraction = manualBrake > 0 || Math.abs(driverCommand.direction()) < 0.5
                ? 0
                : clamp(driverCommand.tractionCommand(), 0, 1) * tractionCapacityFactor;
            return new DynamicsDecision(
                manualBrake > 0 ? TrainDynamicsState.MA_BRAKE : TrainDynamicsState.ACCELERATING,
                manualBrake > 0 ? "DRIVER_SERVICE_BRAKE" : "DRIVER_TRACTION",
                manualTraction,
                manualBrake,
                false,
                stoppingDistance
            );
        }

        double speedMargin = speedLimit - speed;
        double tractionCommand = tractionForSpeedMargin(speedMargin, speedLimit);
        if (power != null && power.powerDeratingFactor() < 0.95 && tractionCommand > 0) {
            return new DynamicsDecision(
                TrainDynamicsState.POWER_DERATED,
                nullTo(power.constraintReason(), "POWER_DERATED"),
                tractionCommand * tractionCapacityFactor,
                0,
                false,
                stoppingDistance
            );
        }
        if (tractionCapacityFactor < 0.95 && tractionCommand > 0) {
            return new DynamicsDecision(
                TrainDynamicsState.OVERLOAD_DERATED,
                loadPolicy.overloaded(loadPolicy.overloadStatus(loadMassKg)) ? "OVERLOAD_TRACTION_LIMIT" : "TRACTION_UNIT_DERATED",
                tractionCommand * tractionCapacityFactor,
                0,
                false,
                stoppingDistance
            );
        }
        if (speedMargin > Math.max(1.5, speedLimit * 0.08)) {
            TrainDynamicsState dynState = departing ? TrainDynamicsState.DEPARTING_STATION : TrainDynamicsState.ACCELERATING;
            String reason = departing ? "DEPARTING_RELEASE" : "SPEED_MARGIN_AVAILABLE";
            return new DynamicsDecision(dynState, reason, tractionCommand * tractionCapacityFactor, 0, false, stoppingDistance);
        }
        if (speedMargin > 0.4) {
            TrainDynamicsState dynState = departing ? TrainDynamicsState.DEPARTING_STATION : TrainDynamicsState.CRUISING;
            String reason = departing ? "DEPARTING_RELEASE" : "NEAR_TARGET_SPEED";
            return new DynamicsDecision(dynState, reason, Math.min(tractionCommand * tractionCapacityFactor, 0.25), 0, false, stoppingDistance);
        }
        TrainDynamicsState dynState = departing ? TrainDynamicsState.DEPARTING_STATION : TrainDynamicsState.COASTING;
        String reason = departing ? "DEPARTING_RELEASE" : "TARGET_SPEED_REACHED";
        return new DynamicsDecision(dynState, reason, 0, 0, false, stoppingDistance);
    }

    private double resolveSpeedLimit(MovementAuthoritySnapshot authority, TrackConstraintSnapshot track) {
        double authorityLimit = authority == null ? properties.getDefaultSpeedLimitMetersPerSecond() : authority.speedLimitMetersPerSecond();
        double trackLimit = track == null ? properties.getDefaultSpeedLimitMetersPerSecond() : track.speedLimitMetersPerSecond();
        return Math.max(0, Math.min(authorityLimit, trackLimit));
    }

    private double resolveMovementAuthorityDistance(TrainStateHolder state, MovementAuthoritySnapshot authority) {
        if (authority == null) {
            return Math.max(0, properties.getDefaultLineLengthMeters() - state.getPositionMeters());
        }
        return Math.max(0, authority.authorityEndMeters() - state.getPositionMeters());
    }

    private double resolveStationDistance(TrackConstraintSnapshot track) {
        double distance = track == null ? NO_STATION_DISTANCE_METERS : track.stationDistanceMeters();
        return Double.isFinite(distance) ? Math.max(0, distance) : NO_STATION_DISTANCE_METERS;
    }

    private double applyDispatchSpeed(double speedLimit, DispatchConstraintSnapshot dispatch) {
        return dispatch == null ? speedLimit : dispatch.applyToSpeedLimit(speedLimit);
    }

    private boolean shouldReleaseStationStop(TrainStateHolder state, DispatchConstraintSnapshot dispatch) {
        if (dispatch == null || !dispatch.releaseStationStop()) {
            return false;
        }
        boolean dwelling = "DWELLING".equals(state.getDynamicsState())
            || "STATION_STOPPED".equals(state.getDynamicsState());
        return dwelling && state.getSpeedMetersPerSecond() <= 0.5;
    }

    private boolean updateDepartureWindow(TrainStateHolder state, DispatchConstraintSnapshot dispatch) {
        if (shouldReleaseStationStop(state, dispatch)) {
            departureOriginMeters = state.getPositionMeters();
            return true;
        }
        if (departureOriginMeters >= 0) {
            double traveled = state.getPositionMeters() - departureOriginMeters;
            if (traveled < DEPARTURE_WINDOW_METERS) {
                return true;
            }
            departureOriginMeters = -1;
        }
        return false;
    }

    private String resolveSelfCheckBlockReason(boolean doorClosed, TrainStateHolder state) {
        if (!doorClosed) {
            return "DOOR_NOT_LOCKED";
        }
        if (!state.isBrakeAvailable() || state.getAvailableBrakeCount() <= 0) {
            return "BRAKE_UNAVAILABLE";
        }
        return "SELF_CHECK_FAILED";
    }

    private DynamicsDecision brakeDecision(TrainDynamicsState state, String reason, double speed, double stoppingDistance, boolean emergencyBrake) {
        return new DynamicsDecision(state, reason, 0, emergencyBrake ? 1 : speed > 0.1 ? 0.8 : 0.6, emergencyBrake, stoppingDistance);
    }

    private double tractionForSpeedMargin(double speedMargin, double speedLimit) {
        return speedMargin <= 0.4 ? 0 : clamp(speedMargin / Math.max(3.0, speedLimit * 0.25), 0, 1);
    }

    private double brakeForDistance(double remainingDistance, double stoppingDistance, double bufferMeters) {
        double shortfall = stoppingDistance + bufferMeters - remainingDistance;
        return clamp(shortfall / Math.max(bufferMeters, 1), 0.2, 1);
    }

    private double stoppingDistanceMeters(double speedMetersPerSecond, double gradient, double brakingFactor) {
        double planningGradient = clamp(gradient, -0.04, 0.04);
        double effectiveDeceleration = clamp(
            stoppingProperties.getServiceBrakeDecelerationMetersPerSecondSquared()
                * clamp(brakingFactor, 0.2, 1.2) + planningGradient * GRAVITY,
            stoppingProperties.getMinimumEffectiveDecelerationMetersPerSecondSquared(),
            stoppingProperties.getMaximumEffectiveDecelerationMetersPerSecondSquared()
        );
        return speedMetersPerSecond * speedMetersPerSecond / (2 * effectiveDeceleration);
    }

    private double stationApproachBufferMeters(double speedMetersPerSecond) {
        return clamp(
            Math.max(stoppingProperties.getMinimumApproachBufferMeters(),
                speedMetersPerSecond * stoppingProperties.getApproachBufferSeconds()),
            stoppingProperties.getMinimumApproachBufferMeters(),
            stoppingProperties.getMaximumApproachBufferMeters());
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String nullTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
