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
        boolean emergencyBrake = authority != null && maDistance <= 0;
        boolean doorClosed = true;

        double tractionCommand = calculateTractionCommand(train, speedLimit, doorClosed, power, dispatch);
        double brakeCommand = calculateBrakeCommand(train, speedLimit, maDistance, emergencyBrake, dispatch);

        if (emergencyBrake) {
            tractionCommand = 0;
            brakeCommand = 1;
        }

        return new VehiclePhysicsInput(
            train.id(),
            train.positionMeters(),
            train.speedMetersPerSecond(),
            EMPTY_MASS_KG + MAX_LOAD_MASS_KG * clamp(train.loadRate(), 0, 1),
            tractionCommand,
            brakeCommand,
            emergencyBrake,
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
            context.deltaSeconds()
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
            input.tractionCommand(),
            input.brakeCommand(),
            input.emergencyBrakeCommand(),
            input.railVoltage(),
            input.powerAvailableWatts(),
            output.faultCode()
        );
    }

    private double calculateTractionCommand(
        TrainState train,
        double speedLimit,
        boolean doorClosed,
        PowerConstraint power,
        DispatchConstraint dispatch
    ) {
        if (
            !doorClosed ||
                dispatch != null && dispatch.holdTrain() ||
                power != null && (!power.energized() || power.powerAvailableWatts() <= 0)
        ) {
            return 0;
        }
        double speedMargin = speedLimit - train.speedMetersPerSecond();
        if (speedMargin <= 0.5) {
            return 0;
        }
        return clamp(speedMargin / Math.max(3.0, speedLimit * 0.25), 0, 1);
    }

    private double calculateBrakeCommand(
        TrainState train,
        double speedLimit,
        double maDistance,
        boolean emergencyBrake,
        DispatchConstraint dispatch
    ) {
        if (emergencyBrake) {
            return 1;
        }
        if (dispatch != null && dispatch.holdTrain()) {
            return train.speedMetersPerSecond() > 0.1 ? 1 : 0.6;
        }

        double overspeed = train.speedMetersPerSecond() - speedLimit;
        if (overspeed > 0) {
            return clamp(overspeed / 3.0, 0.2, 1);
        }

        double stoppingDistance = train.speedMetersPerSecond() * train.speedMetersPerSecond() / (2 * 0.9);
        double brakingBuffer = simulationProperties.getSafetyGapMeters() * 0.5;
        if (maDistance < stoppingDistance + brakingBuffer) {
            return clamp((stoppingDistance + brakingBuffer - maDistance) / Math.max(brakingBuffer, 1), 0.2, 1);
        }
        return 0;
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
        if (dispatch != null && dispatch.holdTrain()) {
            return "DISPATCH_HOLD";
        }
        if (dispatch != null && !"NORMAL".equals(dispatch.reason())) {
            return "DISPATCH_ADJUST";
        }
        return "ATO";
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
