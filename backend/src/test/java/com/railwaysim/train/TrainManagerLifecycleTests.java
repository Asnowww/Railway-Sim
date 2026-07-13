package com.railwaysim.train;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.railwaysim.vehicle.external.ExternalTrainDirection;
import com.railwaysim.vehicle.runtime.VehicleRuntimeHealth;
import com.railwaysim.vehicle.runtime.VehicleRuntimeIntegrationService;
import com.railwaysim.vehicle.runtime.VehicleRuntimeMode;
import com.railwaysim.vehicle.runtime.VehicleRuntimeStepResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrainManagerLifecycleTests {

    @Test
    void lifecycleCommandCreatesCentralMirrorAndRegistersRuntime() {
        StubVehicleRuntime runtime = new StubVehicleRuntime();
        TrainManager manager = manager(runtime);
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
        assertThat(added.lengthMeters()).isEqualTo(118.0);
        assertThat(runtime.registeredTrainIds).contains("TR-003");
        assertThat(manager.vehicleMetadata("TR-003")).get()
            .satisfies(metadata -> {
                assertThat(metadata.trainType()).isEqualTo("B_TYPE_6_CAR");
                assertThat(metadata.parameterSetId()).matches("sha256:[0-9a-f]{64}");
            });
    }

    @Test
    void runtimeStartedTrainCreatesCentralMirrorWithoutReverseRegistration() {
        StubVehicleRuntime runtime = new StubVehicleRuntime();
        TrainManager manager = manager(runtime);
        manager.reset();
        runtime.registeredTrainIds.clear();

        TrainState added = manager.registerRuntimeStartedTrain("TR-105", 8, 450, ExternalTrainDirection.DOWN);

        assertThat(added.id()).isEqualTo("TR-105");
        assertThat(added.positionMeters()).isEqualTo(450);
        assertThat(added.controlSessionState()).isEqualTo("CONNECTING");
        assertThat(added.linkId()).isEqualTo(8);
        assertThat(added.direction()).isEqualTo("DOWN");
        assertThat(added.lengthMeters()).isEqualTo(118.0);
        assertThat(runtime.registeredTrainIds).doesNotContain("TR-105");
    }

    @Test
    void deleteCommandDetachesBeforeEntityAndRuntimeRemoval() {
        StubVehicleRuntime runtime = new StubVehicleRuntime();
        TrainManager manager = manager(runtime);
        manager.reset();
        manager.applyLifecycleCommand(SignalTrainLifecycleCommand.add(
            List.of(SignalTrainLifecycleTrainSpec.add(3, 12, 640, ExternalTrainDirection.DOWN))
        ));

        TrainState detaching = manager.applyLifecycleCommand(SignalTrainLifecycleCommand.delete(
            List.of(SignalTrainLifecycleTrainSpec.delete(3))
        )).get(0);

        assertThat(detaching.controlSessionState()).isEqualTo("SIGNAL_DETACHING");
        manager.tickAll(tick(1), List.of(), List.of(), List.of(), List.of());
        assertThat(manager.state("TR-003")).isPresent();
        manager.tickAll(tick(2), List.of(), List.of(), List.of(), List.of());
        assertThat(manager.state("TR-003")).isEmpty();
        assertThat(runtime.removedTrainIds).contains("TR-003");
    }

    private TrainManager manager(StubVehicleRuntime runtime) {
        StaticInfrastructureCatalog catalog = new StaticInfrastructureCatalog(lineData(), powerData());
        return new TrainManager(
            runtime,
            catalog,
            new RealtimeStateCache(),
            new SimpleEventBus(),
            new VehicleSpecificationCatalog("config/train_params.yaml")
        );
    }

    private TickContext tick(long tick) {
        return new TickContext(tick, 200, 0.2, Instant.parse("2026-07-08T00:00:00Z").plusMillis(tick * 200));
    }

    private OperationalLineData lineData() {
        return new OperationalLineData(
            "demo-line-1", "Demo Line", List.of(), List.of(segment("SEG-1", 1, 0, 5_000)),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }

    private TrackSegmentDefinition segment(String id, int rawSegmentId, double start, double end) {
        return new TrackSegmentDefinition(
            id, rawSegmentId, start, end, end - start, 22.2, 0, 0, 0, 0,
            List.of(), List.of(), id + "-FROM", id + "-TO", "main"
        );
    }

    private OperationalPowerData powerData() {
        return new OperationalPowerData(
            1500, 1000, 0, 2400, 3000, 0.02, true, "DISSIPATE",
            List.of(new PowerSectionDefinition(
                "P01", "Power 01", "SS01", "F01", 0, 5_000, 1500, true,
                "CLOSED", "CLOSED", "NORMAL", "NONE", "UNLOCKED", 0.00005
            )),
            List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }

    private static final class StubVehicleRuntime extends VehicleRuntimeIntegrationService {
        private final List<String> registeredTrainIds = new java.util.ArrayList<>();
        private final List<String> removedTrainIds = new java.util.ArrayList<>();

        private StubVehicleRuntime() {
            super(null, null, null, null, null);
        }

        @Override
        public void register(TrainState train) {
            registeredTrainIds.add(train.id());
        }

        @Override
        public void remove(String trainId) {
            removedTrainIds.add(trainId);
        }

        @Override
        public void clear() {
            registeredTrainIds.clear();
            removedTrainIds.clear();
        }

        @Override
        public VehicleRuntimeStepResult stepFleet(
            TickContext context,
            List<TrainState> trains,
            List<com.railwaysim.signal.MovementAuthority> authorities,
            List<com.railwaysim.track.TrackConstraint> trackConstraints,
            List<com.railwaysim.dispatch.DispatchConstraint> dispatchConstraints,
            List<com.railwaysim.power.PowerConstraint> powerConstraints
        ) {
            return new VehicleRuntimeStepResult(
                List.of(),
                new VehicleRuntimeHealth(VehicleRuntimeMode.EXTERNAL_HTTP, "UP", context.simulatedTime(), 0, "GOOD", trains.size(), "OK"),
                List.of(),
                List.of(),
                List.of()
            );
        }
    }
}
