package com.railwaysim.vehicle.onboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.dispatch.DispatchConstraint;
import com.railwaysim.infrastructure.PowerConfigLoader;
import com.railwaysim.infrastructure.SpreadsheetLineDataLoader;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.infrastructure.YamlLineDataLoader;
import com.railwaysim.power.PowerConstraint;
import com.railwaysim.simulation.TickContext;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.train.TrainEntity;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.SimpleVehicleDynamicsModel;
import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.VehicleLoadPolicy;
import com.railwaysim.vehicle.VehiclePhysicsInput;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import com.railwaysim.vehicle.external.ExternalSegmentMapper;
import com.railwaysim.vehicle.external.ExternalTrainCommand;
import com.railwaysim.vehicle.external.ExternalVehicleCommandMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OnboardTrainSubsystemControlTests {

    @Test
    void deenergizedPowerConstraintCutsTractionAndReportsCurrentCollectionFailure() {
        OnboardTrainSubsystemManager manager = manager();
        TrainState train = new TrainEntity("TR-001", "demo-line-1", 500, 120).state();
        VehiclePhysicsInput input = control(
            manager,
            train,
            null,
            null,
            new PowerConstraint("TR-001", "P01", 0, 0, false, 0, false, false, "DEENERGIZED")
        );

        VehiclePhysicsOutput output = new SimpleVehicleDynamicsModel().step(input);
        TrainStateReport report = manager.buildTrainStateReport(train, input, output);

        assertThat(input.tractionCommand()).isZero();
        ExternalTrainCommand externalCommand = new ExternalVehicleCommandMapper(
            new ExternalSegmentMapper(null, null),
            20
        ).toCommand(input);
        assertThat(externalCommand.command()).isEqualTo(2);
        assertThat(externalCommand.percent()).isGreaterThan(0);
        assertThat(output.faultCode()).isEqualTo("CURRENT_COLLECTION_LOST");
        assertThat(report.currentCollectionStatus()).isEqualTo("LOST");
        assertThat(report.selfCheckStatus()).isEqualTo("FAIL");
        assertThat(report.faultLevel()).isEqualTo(3);
        assertThat(report.availableOperationMode()).isEqualTo("NO_DEPARTURE");
        assertThat(report.dynamicsState()).isEqualTo("POWER_LOSS");
    }

    @Test
    void externalSimulatorFallbackFaultMarksVehicleDataQualityAsFallback() {
        OnboardTrainSubsystemManager manager = manager();
        TrainState train = new TrainEntity("TR-001", "demo-line-1", 500, 120).state();
        VehiclePhysicsInput input = control(
            manager,
            train,
            null,
            null,
            new PowerConstraint("TR-001", "P01", 1500, 3_200_000, true)
        );
        VehiclePhysicsOutput localOutput = new SimpleVehicleDynamicsModel().step(input);
        VehiclePhysicsOutput fallbackOutput = new VehiclePhysicsOutput(
            localOutput.trainId(),
            localOutput.newPositionMeters(),
            localOutput.newSpeedMetersPerSecond(),
            localOutput.accelerationMetersPerSecondSquared(),
            localOutput.tractionForceNewtons(),
            localOutput.brakeForceNewtons(),
            localOutput.regenBrakeForceNewtons(),
            localOutput.tractionPowerWatts(),
            localOutput.railCurrentAmps(),
            localOutput.regenPowerWatts(),
            localOutput.energyConsumedKwh(),
            localOutput.energyRegeneratedKwh(),
            "EXTERNAL_SIM_FALLBACK"
        );

        TrainStateReport report = manager.buildTrainStateReport(train, input, fallbackOutput);

        assertThat(report.dataQuality()).isEqualTo("FALLBACK");
        assertThat(report.faultLevel()).isEqualTo(2);
        assertThat(report.availableOperationMode()).isEqualTo("DEGRADED");
    }

    @Test
    void stationApproachConstraintSelectsStationBrakeState() {
        OnboardTrainSubsystemManager manager = manager();
        VehiclePhysicsInput input = control(
            manager,
            movingTrainState(10.0),
            null,
            new TrackConstraint("TR-001", "SEG-1", 13.33, -0.02, 1_000, 35),
            new PowerConstraint("TR-001", "P01", 1500, 3_200_000, true)
        );

        assertThat(input.dynamicsState()).isEqualTo("STATION_BRAKE");
        assertThat(input.dynamicsConstraintReason()).isEqualTo("STATION_APPROACH");
        assertThat(input.tractionCommand()).isZero();
        assertThat(input.brakeCommand()).isGreaterThan(0);
        assertThat(input.stoppingDistanceMeters()).isGreaterThan(0);
    }

    @Test
    void dispatchReleaseStationStopAllowsDwellingTrainToDepart() {
        OnboardTrainSubsystemManager manager = manager();
        VehiclePhysicsInput input = control(
            manager,
            dwellingTrainState(20),
            null,
            new TrackConstraint("TR-001", "SEG-1", 13.33, 0, 1_000, 0),
            new DispatchConstraint("TR-001", false, 1.0, null, true, "SHORTEN_DWELL"),
            new PowerConstraint("TR-001", "P01", 1500, 3_200_000, true)
        );

        assertThat(input.dynamicsState()).isEqualTo("DEPARTING_STATION");
        assertThat(input.stationDistanceMeters()).isGreaterThan(100_000);
        assertThat(input.tractionCommand()).isGreaterThan(0);
    }

    @Test
    void releasedStationWindowPreventsSameStationRecaptureDuringDeparture() {
        OnboardTrainSubsystemManager manager = manager();
        DispatchConstraint release = new DispatchConstraint("TR-001", false, 1.0, null, true, "SCHEDULE_DWELL_COMPLETE");

        VehiclePhysicsInput releaseTick = control(
            manager,
            dwellingTrainState(20),
            null,
            new TrackConstraint("TR-001", "SEG-1", 13.33, 0, 1_000, 4),
            release,
            new PowerConstraint("TR-001", "P01", 1500, 3_200_000, true)
        );
        VehiclePhysicsInput nextTickStillInsideStationWindow = control(
            manager,
            departedNearStationState(1_246, 0.25),
            null,
            new TrackConstraint("TR-001", "SEG-1", 13.33, 0, 1_000, 4),
            new PowerConstraint("TR-001", "P01", 1500, 3_200_000, true)
        );

        assertThat(releaseTick.dynamicsState()).isEqualTo("DEPARTING_STATION");
        assertThat(releaseTick.stationDistanceMeters()).isGreaterThan(100_000);
        assertThat(nextTickStillInsideStationWindow.dynamicsState()).isEqualTo("DEPARTING_STATION");
        assertThat(nextTickStillInsideStationWindow.stationDistanceMeters()).isGreaterThan(100_000);
        assertThat(nextTickStillInsideStationWindow.tractionCommand()).isGreaterThan(0);
        assertThat(nextTickStillInsideStationWindow.brakeCommand()).isZero();
    }

    @Test
    void dynamicOverloadDeratesTractionAndExtendsStoppingDistanceWithoutDispatchCommand() {
        OnboardTrainSubsystemManager manager = manager();
        TrainState normal = movingTrainState(5.0, 25_200, 6, 6);
        TrainState overloaded = movingTrainState(5.0, 86_400, 6, 6);

        VehiclePhysicsInput normalInput = control(
            manager,
            normal,
            null,
            new TrackConstraint("TR-001", "SEG-1", 13.33, 0, 1_000, 1_000),
            new PowerConstraint("TR-001", "P01", 1500, 3_200_000, true)
        );
        VehiclePhysicsInput overloadedInput = control(
            manager,
            overloaded,
            null,
            new TrackConstraint("TR-001", "SEG-1", 13.33, 0, 1_000, 1_000),
            new PowerConstraint("TR-001", "P01", 1500, 3_200_000, true)
        );
        VehiclePhysicsOutput output = new SimpleVehicleDynamicsModel().step(overloadedInput);
        TrainStateReport report = manager.buildTrainStateReport(overloaded, overloadedInput, output);

        assertThat(normalInput.dynamicsState()).isEqualTo("ACCELERATING");
        assertThat(overloadedInput.dynamicsState()).isEqualTo("OVERLOAD_DERATED");
        assertThat(overloadedInput.dynamicsConstraintReason()).isEqualTo("OVERLOAD_TRACTION_LIMIT");
        assertThat(overloadedInput.tractionCommand()).isLessThan(normalInput.tractionCommand());
        assertThat(overloadedInput.stoppingDistanceMeters()).isGreaterThan(normalInput.stoppingDistanceMeters());
        assertThat(report.overloadStatus()).isEqualTo("CRITICAL_OVERLOAD");
        assertThat(report.vehicleProtectionReason()).isEqualTo("CRITICAL_OVERLOAD");
        assertThat(report.availableOperationMode()).isEqualTo("DEGRADED");
    }

    @Test
    void unavailableBrakeUnitsBlockVehicleSelfCheck() {
        OnboardTrainSubsystemManager manager = manager();

        VehiclePhysicsInput input = control(
            manager,
            movingTrainState(3.0, 25_200, 6, 0),
            null,
            new TrackConstraint("TR-001", "SEG-1", 13.33, 0, 1_000, 1_000),
            new PowerConstraint("TR-001", "P01", 1500, 3_200_000, true)
        );

        assertThat(input.dynamicsState()).isEqualTo("SELF_CHECK_BLOCKED");
        assertThat(input.dynamicsConstraintReason()).isEqualTo("BRAKE_UNAVAILABLE");
        assertThat(input.brakeCommand()).isGreaterThan(0);
    }

    private VehiclePhysicsInput control(
        OnboardTrainSubsystemManager manager,
        TrainState train,
        com.railwaysim.signal.MovementAuthority authority,
        TrackConstraint track,
        PowerConstraint power
    ) {
        return control(manager, train, authority, track, null, power);
    }

    private VehiclePhysicsInput control(
        OnboardTrainSubsystemManager manager,
        TrainState train,
        com.railwaysim.signal.MovementAuthority authority,
        TrackConstraint track,
        DispatchConstraint dispatch,
        PowerConstraint power
    ) {
        return manager.control(new OnboardTrainControlInput(
            train,
            new TickContext(1, 200, 0.2, Instant.now()),
            authority,
            track,
            dispatch,
            power
        )).physicsInput();
    }

    private OnboardTrainSubsystemManager manager() {
        SimulationProperties properties = new SimulationProperties();
        properties.setLineDataPath("../database/线路数据(1).xls");
        properties.setPowerConfigPath("../config/power_third_rail.yaml");
        StaticInfrastructureCatalog catalog = new StaticInfrastructureCatalog(
            properties,
            new SpreadsheetLineDataLoader(),
            new YamlLineDataLoader(),
            new PowerConfigLoader()
        );
        return new OnboardTrainSubsystemManager(properties, catalog);
    }

    private TrainState movingTrainState(double speedMetersPerSecond) {
        return movingTrainState(
            speedMetersPerSecond,
            VehicleLoadPolicy.loadMassFromRate(0.42),
            VehicleLoadPolicy.NOMINAL_TRACTION_UNITS,
            VehicleLoadPolicy.NOMINAL_BRAKE_UNITS
        );
    }

    private TrainState movingTrainState(
        double speedMetersPerSecond,
        double loadMassKg,
        int availableTractionCount,
        int availableBrakeCount
    ) {
        String overloadStatus = VehicleLoadPolicy.overloadStatus(loadMassKg);
        return new TrainState(
            "TR-001",
            "1",
            "TR-001",
            4_200,
            speedMetersPerSecond,
            120,
            4_200,
            4_080,
            VehicleLoadPolicy.loadRateFromMass(loadMassKg),
            loadMassKg,
            overloadStatus,
            availableTractionCount,
            availableBrakeCount,
            VehicleLoadPolicy.vehicleProtectionReason(overloadStatus),
            "RUNNING",
            "ATO",
            false,
            "CLOSED_LOCKED",
            "IDLE",
            "RELEASED",
            "NORMAL",
            true,
            true,
            "PASS",
            0,
            "NORMAL",
            "GOOD",
            "COASTING",
            "TEST",
            13.33,
            1_000,
            35,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            "OK"
        );
    }

    private TrainState dwellingTrainState(int dwellElapsedSeconds) {
        return new TrainState(
            "TR-001",
            "1",
            "TR-001",
            "IN_SERVICE",
            "ATTACHED",
            "ATTACHED",
            "TEST",
            0,
            "UNKNOWN",
            1_250,
            0,
            120,
            1_250,
            1_130,
            0.42,
            VehicleLoadPolicy.loadMassFromRate(0.42),
            "NORMAL",
            VehicleLoadPolicy.NOMINAL_TRACTION_UNITS,
            VehicleLoadPolicy.NOMINAL_BRAKE_UNITS,
            "NONE",
            "DWELLING",
            "STATION_CONTROL",
            true,
            "CLOSED_LOCKED",
            "IDLE",
            "APPLYING",
            "NORMAL",
            true,
            true,
            "PASS",
            0,
            "NORMAL",
            "GOOD",
            "STATION_STOPPED",
            "TEST",
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            "OK",
            "S02",
            dwellElapsedSeconds,
            null
        );
    }

    private TrainState departedNearStationState(double positionMeters, double speedMetersPerSecond) {
        return new TrainState(
            "TR-001",
            "1",
            "TR-001",
            "IN_SERVICE",
            "ATTACHED",
            "ATTACHED",
            "TEST",
            0,
            "UNKNOWN",
            positionMeters,
            speedMetersPerSecond,
            120,
            positionMeters,
            Math.max(0, positionMeters - 120),
            0.42,
            VehicleLoadPolicy.loadMassFromRate(0.42),
            "NORMAL",
            VehicleLoadPolicy.NOMINAL_TRACTION_UNITS,
            VehicleLoadPolicy.NOMINAL_BRAKE_UNITS,
            "NONE",
            "RUNNING",
            "ATO",
            false,
            "CLOSED_LOCKED",
            "IDLE",
            "RELEASED",
            "NORMAL",
            true,
            true,
            "PASS",
            0,
            "NORMAL",
            "GOOD",
            "ACCELERATING",
            "SCHEDULE_DWELL_COMPLETE",
            13.33,
            0,
            1_000,
            4,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            "OK",
            null,
            0,
            Instant.now().toString()
        );
    }
}
