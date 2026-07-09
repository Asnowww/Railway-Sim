package com.railwaysim.train;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.OperationalLineData.TrackSegmentDefinition;
import com.railwaysim.infrastructure.OperationalPowerData;
import com.railwaysim.infrastructure.OperationalPowerData.PowerSectionDefinition;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleCommand;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleTrainSpec;
import com.railwaysim.simulation.RealtimeStateCache;
import com.railwaysim.simulation.TickContext;
import com.railwaysim.simulation.event.SimpleEventBus;
import com.railwaysim.vehicle.SimpleVehicleDynamicsModel;
import com.railwaysim.vehicle.VehiclePhysicsClient;
import com.railwaysim.vehicle.external.ExternalTrainDirection;
import com.railwaysim.vehicle.onboard.OnboardTrainSubsystemManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrainManagerLifecycleTests {

    @Test
    void lifecycleCommandCreatesExternalControlSessionInsteadOfInternalController() {
        TrainManager manager = manager();
        manager.reset();

        TrainState added = manager.applyLifecycleCommand(SignalTrainLifecycleCommand.add(
            List.of(SignalTrainLifecycleTrainSpec.add(3, 12, 640, ExternalTrainDirection.DOWN))
        )).get(0);

        assertThat(added.id()).isEqualTo("TR-003");
        assertThat(added.controlSessionState()).isEqualTo("CONNECTING");
        assertThat(added.signalNetworkStatus()).isEqualTo("NOT_ATTACHED");
        assertThat(added.powerNetworkStatus()).isEqualTo("NOT_ATTACHED");
        assertThat(added.linkId()).isEqualTo(12);
        assertThat(added.direction()).isEqualTo("DOWN");
    }

    @Test
    void deleteCommandDetachesBeforeEntityRemoval() {
        TrainManager manager = manager();
        manager.reset();
        manager.applyLifecycleCommand(SignalTrainLifecycleCommand.add(
            List.of(SignalTrainLifecycleTrainSpec.add(3, 12, 640, ExternalTrainDirection.DOWN))
        ));

        TrainState detaching = manager.applyLifecycleCommand(SignalTrainLifecycleCommand.delete(
            List.of(SignalTrainLifecycleTrainSpec.delete(3))
        )).get(0);

        assertThat(detaching.controlSessionState()).isEqualTo("SIGNAL_DETACHING");
        assertThat(manager.state("TR-003")).isPresent();

        manager.tickAll(tick(1), List.of(), List.of(), List.of(), List.of());
        assertThat(manager.state("TR-003")).isPresent();

        manager.tickAll(tick(2), List.of(), List.of(), List.of(), List.of());
        assertThat(manager.state("TR-003")).isEmpty();
    }

    private TrainManager manager() {
        StaticInfrastructureCatalog catalog = new StaticInfrastructureCatalog(lineData(), powerData());
        SimulationProperties properties = new SimulationProperties();
        VehiclePhysicsClient physicsClient = inputs -> inputs.stream()
            .map(new SimpleVehicleDynamicsModel()::step)
            .toList();
        return new TrainManager(
            new OnboardTrainSubsystemManager(properties, catalog),
            physicsClient,
            catalog,
            new RealtimeStateCache(),
            new SimpleEventBus()
        );
    }

    private TickContext tick(long tick) {
        return new TickContext(tick, 200, 0.2, Instant.parse("2026-07-08T00:00:00Z").plusMillis(tick * 200));
    }

    private OperationalLineData lineData() {
        return new OperationalLineData(
            "demo-line-1",
            "Demo Line",
            List.of(),
            List.of(segment("SEG-1", 1, 0, 5_000)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private TrackSegmentDefinition segment(String id, int rawSegmentId, double start, double end) {
        return new TrackSegmentDefinition(
            id,
            rawSegmentId,
            start,
            end,
            end - start,
            22.2,
            0,
            0,
            0,
            0,
            List.of(),
            List.of(),
            id + "-FROM",
            id + "-TO",
            "main"
        );
    }

    private OperationalPowerData powerData() {
        return new OperationalPowerData(
            1500,
            1000,
            0,
            2400,
            3000,
            0.02,
            true,
            "DISSIPATE",
            List.of(new PowerSectionDefinition(
                "P01",
                "Power 01",
                "SS01",
                "F01",
                0,
                5_000,
                1500,
                true,
                "CLOSED",
                "CLOSED",
                "NORMAL",
                "NONE",
                "UNLOCKED",
                0.00005
            )),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }
}
