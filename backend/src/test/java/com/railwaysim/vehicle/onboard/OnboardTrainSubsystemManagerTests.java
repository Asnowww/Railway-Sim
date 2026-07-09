package com.railwaysim.vehicle.onboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.infrastructure.PowerConfigLoader;
import com.railwaysim.infrastructure.SpreadsheetLineDataLoader;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.infrastructure.YamlLineDataLoader;
import com.railwaysim.power.PowerConstraint;
import com.railwaysim.simulation.TickContext;
import com.railwaysim.train.TrainEntity;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.SimpleVehicleDynamicsModel;
import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.VehiclePhysicsInput;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OnboardTrainSubsystemManagerTests {

    @Test
    void registersSingleTrainSubsystemAndProducesControlOutput() {
        OnboardTrainSubsystemManager manager = manager();

        OnboardTrainRegistration registration = manager.register("TR-009");
        VehiclePhysicsInput input = manager.control(new OnboardTrainControlInput(
            new TrainEntity("TR-009", "demo-line-1", 500, 120).state(),
            new TickContext(1, 200, 0.2, Instant.parse("2026-07-08T00:00:00Z")),
            null,
            null,
            null,
            new PowerConstraint("TR-009", "P01", 1500, 3_200_000, true)
        )).physicsInput();

        assertThat(registration.subsystemId()).isEqualTo("ONBOARD-TR-009");
        assertThat(registration.mode()).isEqualTo("IN_PROCESS_SIMULATED");
        assertThat(manager.registeredCount()).isEqualTo(1);
        assertThat(manager.nodeStates())
            .singleElement()
            .satisfies(state -> {
                assertThat(state.trainId()).isEqualTo("TR-009");
                assertThat(state.requestedMode()).isEqualTo("IN_PROCESS");
                assertThat(state.activeMode()).isEqualTo("IN_PROCESS_SIMULATED");
                assertThat(state.connectionState()).isEqualTo("ONLINE");
                assertThat(state.dataQuality()).isEqualTo("GOOD");
            });
        assertThat(input.trainId()).isEqualTo("TR-009");
        assertThat(input.dynamicsState()).isIn("ACCELERATING", "CRUISING", "COASTING");
    }

    @Test
    void buildsStateReportFromTheSameOnboardBoundary() {
        OnboardTrainSubsystemManager manager = manager();
        VehiclePhysicsInput input = manager.control(new OnboardTrainControlInput(
            new TrainEntity("TR-010", "demo-line-1", 500, 120).state(),
            new TickContext(1, 200, 0.2, Instant.parse("2026-07-08T00:00:00Z")),
            null,
            null,
            null,
            new PowerConstraint("TR-010", "P01", 0, 0, false, 0, false, false, "DEENERGIZED")
        )).physicsInput();
        VehiclePhysicsOutput output = new SimpleVehicleDynamicsModel().step(input);

        TrainStateReport report = manager.buildTrainStateReport(null, input, output);

        assertThat(report.trainId()).isEqualTo("TR-010");
        assertThat(report.dynamicsState()).isEqualTo("POWER_LOSS");
        assertThat(report.currentCollectionStatus()).isEqualTo("LOST");
    }

    @Test
    void externalHttpModeFallsBackToLocalNodeAndMarksRegistry() {
        SimulationProperties properties = new SimulationProperties();
        properties.setLineDataSource("file");
        properties.setOnboardSubsystemMode(OnboardTrainSubsystemMode.EXTERNAL_HTTP);
        StaticInfrastructureCatalog catalog = new StaticInfrastructureCatalog(
            properties,
            new SpreadsheetLineDataLoader(),
            new YamlLineDataLoader(),
            new PowerConfigLoader()
        );
        OnboardTrainSubsystemManager manager = new OnboardTrainSubsystemManager(
            properties,
            new LocalOnboardTrainSubsystemClient(properties, catalog),
            new ThrowingOnboardTrainSubsystemClient(),
            new OnboardTrainNodeRegistry()
        );

        OnboardTrainRegistration registration = manager.register("TR-011");
        VehiclePhysicsInput input = manager.control(new OnboardTrainControlInput(
            new TrainEntity("TR-011", "demo-line-1", 500, 120).state(),
            new TickContext(1, 200, 0.2, Instant.parse("2026-07-08T00:00:00Z")),
            null,
            null,
            null,
            new PowerConstraint("TR-011", "P01", 1500, 3_200_000, true)
        )).physicsInput();

        assertThat(registration.mode()).isEqualTo("IN_PROCESS_SIMULATED");
        assertThat(input.trainId()).isEqualTo("TR-011");
        assertThat(manager.nodeStates())
            .singleElement()
            .satisfies(state -> {
                assertThat(state.requestedMode()).isEqualTo("EXTERNAL_HTTP");
                assertThat(state.activeMode()).isEqualTo("IN_PROCESS_SIMULATED");
                assertThat(state.connectionState()).isEqualTo("FALLBACK");
                assertThat(state.dataQuality()).isEqualTo("FALLBACK");
                assertThat(state.lastError()).contains("external node unavailable");
            });
    }

    private OnboardTrainSubsystemManager manager() {
        SimulationProperties properties = new SimulationProperties();
        properties.setLineDataSource("file");
        StaticInfrastructureCatalog catalog = new StaticInfrastructureCatalog(
            properties,
            new SpreadsheetLineDataLoader(),
            new YamlLineDataLoader(),
            new PowerConfigLoader()
        );
        return new OnboardTrainSubsystemManager(properties, catalog);
    }

    private static final class ThrowingOnboardTrainSubsystemClient implements OnboardTrainSubsystemClient {

        @Override
        public OnboardTrainRegistration register(String trainId) {
            throw new IllegalStateException("external node unavailable");
        }

        @Override
        public OnboardTrainControlOutput control(OnboardTrainControlInput input) {
            throw new IllegalStateException("external node unavailable");
        }

        @Override
        public TrainStateReport buildTrainStateReport(
            TrainState train,
            VehiclePhysicsInput input,
            VehiclePhysicsOutput output
        ) {
            throw new IllegalStateException("external node unavailable");
        }

        @Override
        public void remove(String trainId) {
            throw new IllegalStateException("external node unavailable");
        }

        @Override
        public void clear() {
            throw new IllegalStateException("external node unavailable");
        }
    }
}
