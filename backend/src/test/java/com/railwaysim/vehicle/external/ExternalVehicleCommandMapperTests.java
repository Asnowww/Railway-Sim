package com.railwaysim.vehicle.external;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.vehicle.VehiclePhysicsInput;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalVehicleCommandMapperTests {

    @Test
    void mapsInternalTractionBrakeCoastAndEmergencyBrakeToProtocolCommand() {
        ExternalVehicleCommandMapper mapper = new ExternalVehicleCommandMapper(
            new ExternalSegmentMapper(null, null),
            20
        );

        List<ExternalTrainCommand> commands = mapper.toCommands(List.of(
            input("TR-001", 0.73, 0, false),
            input("TR-002", 0.2, 0.456, false),
            input("TR-003", 0.5, 0.1, true),
            input("TR-004", 0, 0, false)
        ));

        assertThat(commands).extracting(ExternalTrainCommand::trainNo).containsExactly(1, 2, 3, 4);
        assertThat(commands.get(0).command()).isEqualTo(1);
        assertThat(commands.get(0).percent()).isEqualTo(73);
        assertThat(commands.get(1).command()).isEqualTo(2);
        assertThat(commands.get(1).percent()).isEqualTo(46);
        assertThat(commands.get(2).command()).isEqualTo(2);
        assertThat(commands.get(2).percent()).isEqualTo(100);
        assertThat(commands.get(3).command()).isZero();
        assertThat(commands.get(3).percent()).isZero();
    }

    private VehiclePhysicsInput input(
        String trainId,
        double tractionCommand,
        double brakeCommand,
        boolean emergencyBrake
    ) {
        return new VehiclePhysicsInput(
            trainId,
            100.0,
            5.0,
            220_000.0,
            tractionCommand,
            brakeCommand,
            emergencyBrake,
            20.0,
            900.0,
            0.0,
            1_000.0,
            1_500.0,
            3_000_000.0,
            true,
            0.9,
            0.0,
            0.0,
            0.1,
            "TEST",
            "TEST",
            1_000.0,
            0.0
        );
    }
}
