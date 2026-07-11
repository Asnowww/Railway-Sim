package com.railwaysim.signal.vehicle;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.train.ExternalTrainControlSession;
import com.railwaysim.train.TrainEntity;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.external.ExternalTrainDirection;
import com.railwaysim.vehicle.protocol.TrainOperationalTelemetry;
import org.junit.jupiter.api.Test;

class SignalVehicleInterfaceTests {

    @Test
    void vehicleStatusProjectsTrainStateForSignalConsumption() {
        VehicleSignalStatus status = VehicleSignalStatus.from(
            new TrainEntity("TR-001", "demo-line-1", 500, 120, 0.42).state()
        );

        assertThat(status.trainId()).isEqualTo("TR-001");
        assertThat(status.headMileage()).isEqualTo(500);
        assertThat(status.doorState()).isEqualTo("CLOSED_LOCKED");
        assertThat(status.vehicleProtectionReason()).isEqualTo("NONE");
        assertThat(status.operationMode()).isEqualTo("ATO");
        assertThat(status.faultCode()).isEqualTo("OK");
        assertThat(status.driverConsoleState().doorModeSwitchState())
            .isEqualTo(SignalDriverConsoleState.DoorModeSwitch.AUTOMATIC);
        assertThat(status.driverConsoleState().atoStartFlag()).isTrue();
    }

    @Test
    void signalCommandProjectsMovementAuthorityWithoutDispatchFields() {
        SignalVehicleCommand command = SignalVehicleCommand.fromAuthority(
            new MovementAuthority("TR-001", 1_200, 13.33, "前方区段空闲", "SEG-1")
        );

        assertThat(command.trainId()).isEqualTo("TR-001");
        assertThat(command.authorityEndMeters()).isEqualTo(1_200);
        assertThat(command.speedLimitMetersPerSecond()).isEqualTo(13.33);
        assertThat(command.tractionCutoff()).isFalse();
        assertThat(command.emergencyBrakeCommand()).isFalse();
        assertThat(command.cabDisplay().currentDrivingMode()).isEqualTo(SignalCabDisplayState.DrivingMode.ATO);
        assertThat(command.cabDisplay().departureInfo()).isEqualTo(SignalCabDisplayState.DepartureInfo.DEPART);
    }

    @Test
    void signalCommandAppliesVehicleReportedFaultSpeedLimit() {
        TrainEntity train = new TrainEntity("TR-001", "demo-line-1", 500, 120, 0.42);
        train.applyOperationalTelemetry(new TrainOperationalTelemetry(
            1,
            8.0,
            500,
            ExternalTrainDirection.DOWN,
            25_200,
            2.0,
            false,
            6,
            6
        ));

        SignalVehicleCommand command = SignalVehicleCommand.fromAuthority(
            train.state(),
            new MovementAuthority("TR-001", 1_200, 13.33, "route ready", "SEG-1")
        );

        assertThat(command.speedLimitMetersPerSecond()).isEqualTo(2.0);
        assertThat(command.reason()).isEqualTo("VEHICLE_FAULT_SPEED_LIMIT");
        assertThat(command.tractionCutoff()).isFalse();
        assertThat(command.cabDisplay().speedLimitMetersPerSecond()).isEqualTo(2.0);
        assertThat(VehicleSignalStatus.from(train.state()).vehicleFaultSpeedLimitMetersPerSecond()).isEqualTo(2.0);
    }

    @Test
    void signalCommandFallsBackToEmergencyBrakeWhenMovementAuthorityIsMissingOrExhausted() {
        TrainState train = new TrainEntity("TR-001", "demo-line-1", 500, 120, 0.42).state();

        SignalVehicleCommand missingAuthority = SignalVehicleCommand.fromAuthority(train, null);
        SignalVehicleCommand exhaustedAuthority = SignalVehicleCommand.fromAuthority(
            train,
            new MovementAuthority("TR-001", 500, 13.33, "MA exhausted", "SEG-1")
        );

        assertThat(missingAuthority.tractionCutoff()).isTrue();
        assertThat(missingAuthority.serviceBrakeCommand()).isTrue();
        assertThat(missingAuthority.emergencyBrakeCommand()).isTrue();
        assertThat(missingAuthority.reason()).isEqualTo("NO_MOVEMENT_AUTHORITY");
        assertThat(missingAuthority.cabDisplay().tractionBrakeInfo())
            .isEqualTo(SignalCabDisplayState.TractionBrakeInfo.EMERGENCY_BRAKING);
        assertThat(missingAuthority.cabDisplay().departureInfo()).isEqualTo(SignalCabDisplayState.DepartureInfo.HOLD);
        assertThat(exhaustedAuthority.emergencyBrakeCommand()).isTrue();
        assertThat(exhaustedAuthority.reason()).isEqualTo("MOVEMENT_AUTHORITY_EXHAUSTED");
    }

    @Test
    void signalCommandBlocksVehicleBeforeExternalControlSessionIsInService() {
        TrainState train = new TrainEntity("TR-001", "demo-line-1", 500, 120, 0.42)
            .state(ExternalTrainControlSession.connecting("TR-001", 1, 500, ExternalTrainDirection.DOWN));

        SignalVehicleCommand command = SignalVehicleCommand.fromAuthority(
            train,
            new MovementAuthority("TR-001", 1_200, 13.33, "route ready", "SEG-1")
        );

        assertThat(command.tractionCutoff()).isTrue();
        assertThat(command.serviceBrakeCommand()).isTrue();
        assertThat(command.emergencyBrakeCommand()).isFalse();
        assertThat(command.reason()).isEqualTo("CONTROL_SESSION_CONNECTING");
        assertThat(command.cabDisplay().maximumAvailableDrivingMode()).isEqualTo(SignalCabDisplayState.DrivingMode.RM);
        assertThat(command.cabDisplay().departureInfo()).isEqualTo(SignalCabDisplayState.DepartureInfo.HOLD);
    }

    @Test
    void vehicleOperationalTelemetryUpdatesSignalFacingStatus() {
        TrainEntity train = new TrainEntity("TR-001", "demo-line-1", 500, 120, 0.42);

        train.applyOperationalTelemetry(new TrainOperationalTelemetry(
            1,
            12.34,
            987.65,
            ExternalTrainDirection.DOWN,
            105_000,
            2.0,
            true,
            4,
            5
        ));
        VehicleSignalStatus status = VehicleSignalStatus.from(train.state());

        assertThat(status.headMileage()).isEqualTo(987.65);
        assertThat(status.speedMetersPerSecond()).isEqualTo(12.34);
        assertThat(status.loadMassKg()).isEqualTo(105_000);
        assertThat(status.overloadStatus()).isEqualTo("CRITICAL_OVERLOAD");
        assertThat(status.availableTractionCount()).isEqualTo(4);
        assertThat(status.availableBrakeCount()).isEqualTo(4);
        assertThat(status.brakeAvailable()).isTrue();
        assertThat(status.faultCode()).isEqualTo("ATP_BRAKE");
        assertThat(status.vehicleFaultSpeedLimitMetersPerSecond()).isEqualTo(2.0);
        assertThat(status.availableOperationMode()).isEqualTo("NO_DEPARTURE");
        assertThat(status.driverConsoleState().doorModeSwitchState())
            .isEqualTo(SignalDriverConsoleState.DoorModeSwitch.MANUAL);
        assertThat(status.driverConsoleState().masterHandleState())
            .isEqualTo(SignalDriverConsoleState.MasterHandleState.FAST_BRAKE);
    }
}
