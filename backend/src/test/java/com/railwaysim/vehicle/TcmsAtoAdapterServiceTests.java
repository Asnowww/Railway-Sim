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
import com.railwaysim.train.TrainEntity;
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
    }

    private TcmsAtoAdapterService adapter() {
        SimulationProperties properties = new SimulationProperties();
        properties.setLineDataPath("../config/line-demo.yaml");
        properties.setPowerConfigPath("../config/power_third_rail.yaml");
        StaticInfrastructureCatalog catalog = new StaticInfrastructureCatalog(
            properties,
            new SpreadsheetLineDataLoader(),
            new YamlLineDataLoader(),
            new PowerConfigLoader()
        );
        return new TcmsAtoAdapterService(properties, catalog);
    }
}
