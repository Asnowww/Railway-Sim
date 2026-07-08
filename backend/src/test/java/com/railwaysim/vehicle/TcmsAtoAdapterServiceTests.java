package com.railwaysim.vehicle;

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
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TcmsAtoAdapterServiceTests {

    @Test
    void deenergizedPowerConstraintCutsTractionAndReportsCurrentCollectionFailure() {
        TcmsAtoAdapterService adapter = adapter();
        VehiclePhysicsInput input = adapter.buildVehiclePhysicsInput(
            new TrainEntity("TR-001", "demo-line-1", 500, 120).state(),
            new TickContext(1, 200, 0.2, Instant.now()),
            null,
            null,
            new PowerConstraint("TR-001", "P01", 0, 0, false, 0, false, false, "DEENERGIZED"),
            DispatchConstraint.none("TR-001")
        );

        VehiclePhysicsOutput output = new SimpleVehicleDynamicsModel().step(input);
        TrainStateReport report = adapter.buildTrainStateReport(input, output, DispatchConstraint.none("TR-001"));

        assertThat(input.tractionCommand()).isZero();
        assertThat(output.faultCode()).isEqualTo("CURRENT_COLLECTION_LOST");
        assertThat(report.currentCollectionStatus()).isEqualTo("LOST");
        assertThat(report.selfCheckStatus()).isEqualTo("FAIL");
        assertThat(report.faultLevel()).isEqualTo(3);
        assertThat(report.availableOperationMode()).isEqualTo("NO_DEPARTURE");
        assertThat(report.dynamicsState()).isEqualTo("POWER_LOSS");
    }

    @Test
    void stationApproachConstraintSelectsStationBrakeState() {
        TcmsAtoAdapterService adapter = adapter();
        VehiclePhysicsInput input = adapter.buildVehiclePhysicsInput(
            movingTrainState(10.0),
            new TickContext(1, 200, 0.2, Instant.now()),
            null,
            new TrackConstraint("TR-001", "SEG-1", 13.33, -0.02, 1_000, 35),
            new PowerConstraint("TR-001", "P01", 1500, 3_200_000, true),
            DispatchConstraint.none("TR-001")
        );

        assertThat(input.dynamicsState()).isEqualTo("STATION_BRAKE");
        assertThat(input.dynamicsConstraintReason()).isEqualTo("STATION_APPROACH");
        assertThat(input.tractionCommand()).isZero();
        assertThat(input.brakeCommand()).isGreaterThan(0);
        assertThat(input.stoppingDistanceMeters()).isGreaterThan(0);
    }

    private TcmsAtoAdapterService adapter() {
        SimulationProperties properties = new SimulationProperties();
        properties.setLineDataPath("../database/线路数据(1).xls");
        properties.setPowerConfigPath("../config/power_third_rail.yaml");
        StaticInfrastructureCatalog catalog = new StaticInfrastructureCatalog(
            properties,
            new SpreadsheetLineDataLoader(),
            new YamlLineDataLoader(),
            new PowerConfigLoader()
        );
        return new TcmsAtoAdapterService(properties, catalog);
    }

    private TrainState movingTrainState(double speedMetersPerSecond) {
        return new TrainState(
            "TR-001",
            "1",
            "TR-001",
            4_200,
            speedMetersPerSecond,
            120,
            4_200,
            4_080,
            0.42,
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
            "OK",
            null,
            0,
            null
        );
    }
}
