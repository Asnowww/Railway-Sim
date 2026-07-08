package com.railwaysim.signal.vehicle;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.train.TrainEntity;
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
    }

    @Test
    void signalCommandProjectsMovementAuthorityWithoutDispatchFields() {
        SignalVehicleCommand command = SignalVehicleCommand.fromAuthority(
            new MovementAuthority("TR-001", 1_200, 13.33, "前方区段空闲")
        );

        assertThat(command.trainId()).isEqualTo("TR-001");
        assertThat(command.authorityEndMeters()).isEqualTo(1_200);
        assertThat(command.speedLimitMetersPerSecond()).isEqualTo(13.33);
        assertThat(command.tractionCutoff()).isFalse();
        assertThat(command.emergencyBrakeCommand()).isFalse();
    }
}
