package com.railwaysim.power;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.railwaysim.config.ExternalPowerNetworkProperties;
import com.railwaysim.config.SimulationProperties;
import com.railwaysim.infrastructure.PowerConfigLoader;
import com.railwaysim.infrastructure.OperationalPowerData;
import com.railwaysim.infrastructure.SpreadsheetLineDataLoader;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.infrastructure.YamlLineDataLoader;
import com.railwaysim.power.external.PowerNetworkStateSnapshot;
import com.railwaysim.simulation.RealtimeStateCache;
import com.railwaysim.simulation.event.PowerFaultStateChangedEvent;
import com.railwaysim.simulation.event.RegenerativeEnergyAbsorbedEvent;
import com.railwaysim.simulation.event.SimpleEventBus;
import com.railwaysim.train.TrainEntity;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class PowerServiceTests {

    @Test
    void updateFromVehicleOutputsAggregatesRegenerativePowerBySection() {
        SimpleEventBus eventBus = new SimpleEventBus();
        PowerService powerService = powerService(eventBus);
        powerService.reset();

        powerService.updateFromVehicleOutputs(List.of(
            output("TR-001", 500, 900, 900_000, 0),
            output("TR-002", 800, 0, 0, 200_000)
        ));

        PowerSectionState section = powerService.states().stream()
            .filter(state -> state.id().equals("P01"))
            .findFirst()
            .orElseThrow();
        assertThat(section.affectedTrainIds()).containsExactlyInAnyOrder("TR-001", "TR-002");
        assertThat(section.regenPowerWatts()).isEqualTo(200_000);
        assertThat(section.absorbedRegenPowerWatts()).isEqualTo(200_000);
        assertThat(section.unabsorbedRegenPowerWatts()).isZero();
        assertThat(section.availablePowerWatts()).isGreaterThan(0);
        assertThat(eventBus.drain()).anySatisfy(event -> assertThat(event).isInstanceOf(RegenerativeEnergyAbsorbedEvent.class));
    }

    @Test
    void injectedDeenergizedFaultMakesPowerConstraintUnavailable() {
        SimpleEventBus eventBus = new SimpleEventBus();
        PowerService powerService = powerService(eventBus);
        powerService.reset();

        powerService.injectPowerFault("P01", "DEENERGIZED");
        powerService.updateFromVehicleOutputs(List.of(output("TR-001", 500, 0, 0, 0)));

        PowerConstraint constraint = powerService.constraintsForTrains(List.of(
            new TrainEntity("TR-001", "demo-line-1", 500, 120).state()
        )).get(0);

        assertThat(constraint.currentCollectionAvailable()).isFalse();
        assertThat(constraint.powerAvailableWatts()).isZero();
        assertThat(constraint.constraintReason()).isEqualTo("DEENERGIZED");
        assertThat(eventBus.drain()).anySatisfy(event -> assertThat(event).isInstanceOf(PowerFaultStateChangedEvent.class));
    }

    @Test
    void illegalTrainPositionsProduceUnknownSafeConstraint() {
        StaticInfrastructureCatalog catalog = catalog();
        PowerConstraintService constraintService = new PowerConstraintService(catalog);
        double outOfRange = catalog.powerData().sections().stream()
            .mapToDouble(OperationalPowerData.PowerSectionDefinition::endMeters)
            .max()
            .orElse(0) + 1;

        List<PowerConstraint> constraints = constraintService.constraintsForTrains(
            List.of(
                new TrainEntity("TR-NEG", "demo-line-1", -1, 120).state(),
                new TrainEntity("TR-END", "demo-line-1", outOfRange, 120).state(),
                new TrainEntity("TR-INF", "demo-line-1", Double.POSITIVE_INFINITY, 120).state()
            ),
            constraintService.initializeStates(new PowerTopologyService(catalog).defaultSnapshot())
        );

        assertThat(constraints).allSatisfy(constraint -> {
            assertThat(constraint.sectionId()).isEqualTo("UNKNOWN");
            assertThat(constraint.powerAvailableWatts()).isZero();
            assertThat(constraint.currentCollectionAvailable()).isFalse();
            assertThat(constraint.constraintReason()).isEqualTo("POWER_SECTION_UNKNOWN");
        });
    }

    @Test
    void sectionStateIncludesExternalProjectionAndDeviceViews() {
        PowerService powerService = powerService(new SimpleEventBus());
        powerService.reset();
        powerService.updateFromVehicleOutputs(List.of(output("TR-001", 500, 300, 200_000, 0)));

        PowerSectionState section = powerService.section("P01");
        assertThat(section.supplyMode()).isEqualTo("DOUBLE_END");
        assertThat(section.isolatorStatus()).isEqualTo("CLOSED");
        assertThat(section.substationAvailability()).isEqualTo("AVAILABLE");
        assertThat(section.externalDataQuality()).isEqualTo("GOOD");
        assertThat(section.voltageComparisonStatus()).isEqualTo("NO_EXTERNAL_DATA");
        assertThat(section.externalVoltage()).isZero();
        assertThat(section.strayCurrentRiskLevel()).isEqualTo("NORMAL");
        assertThat(powerService.substations()).isNotEmpty();
        assertThat(powerService.isolators()).isNotEmpty();
        assertThat(powerService.strayCurrentRisks()).isNotEmpty();
    }

    @Test
    void loadSnapshotsAggregateVehicleOutputsByPowerSection() {
        PowerConstraintService constraintService = new PowerConstraintService(catalog());

        List<PowerSectionLoadSnapshot> loads = constraintService.loadSnapshots(List.of(
            output("TR-001", 500, 900, 900_000, 0),
            output("TR-002", 800, 500, 300_000, 100_000)
        ));

        assertThat(loads).hasSize(1);
        PowerSectionLoadSnapshot load = loads.get(0);
        assertThat(load.powerSectionId()).isEqualTo("P01");
        assertThat(load.trainIds()).containsExactlyInAnyOrder("TR-001", "TR-002");
        assertThat(load.currentAmps()).isEqualTo(1_400);
        assertThat(load.tractionPowerWatts()).isEqualTo(1_200_000);
        assertThat(load.regenPowerWatts()).isEqualTo(100_000);
    }

    @Test
    void externalVoltageIsAuthoritativeWhileCentralCalculationRemainsAsDeviationDiagnostic() {
        PowerConstraintService constraintService = new PowerConstraintService(catalog());
        VehiclePhysicsOutput output = output("TR-001", 500, 1_200, 900_000, 0);

        PowerSectionState section = constraintService.calculateStates(
            List.of(output),
            externalSnapshot(1_250),
            Map.of(),
            Map.of(),
            Map.of()
        ).stream()
            .filter(state -> state.id().equals("P01"))
            .findFirst()
            .orElseThrow();

        // Central diagnostic calculation is 1368 V; current state mirrors authoritative 9200 at 1250 V.
        assertThat(section.voltage()).isEqualTo(1_250);
        assertThat(section.externalVoltage()).isEqualTo(1_250);
        assertThat(section.voltageDeviation()).isCloseTo(-118, within(0.001));
        assertThat(section.voltageComparisonStatus()).isEqualTo("DEVIATED");
        assertThat(section.externalSupportReason()).isEqualTo("test external voltage");
    }

    private PowerService powerService(SimpleEventBus eventBus) {
        StaticInfrastructureCatalog catalog = catalog();
        PowerTopologyService topologyService = new PowerTopologyService(catalog);
        PowerIntegrationService integrationService = new PowerIntegrationService(
            new ExternalPowerNetworkProperties(),
            topologyService,
            () -> false,
            RestClient.builder()
        );
        PowerConstraintService constraintService = new PowerConstraintService(catalog);
        return new PowerService(
            topologyService,
            integrationService,
            constraintService,
            new RealtimeStateCache(),
            eventBus
        );
    }

    private StaticInfrastructureCatalog catalog() {
        SimulationProperties properties = new SimulationProperties();
        properties.setLineDataPath("../config/line-demo.yaml");
        properties.setPowerConfigPath("../config/power_third_rail.yaml");
        return new StaticInfrastructureCatalog(
            properties,
            new SpreadsheetLineDataLoader(),
            new YamlLineDataLoader(),
            new PowerConfigLoader()
        );
    }

    private PowerNetworkStateSnapshot externalSnapshot(double externalVoltage) {
        return new PowerNetworkStateSnapshot(
            Instant.parse("2026-07-09T00:00:00Z"),
            "UP",
            "GOOD",
            List.of(new PowerNetworkStateSnapshot.SubstationSnapshot(
                "SS01",
                "SS01",
                "DOUBLE_END",
                "AVAILABLE",
                List.of(new PowerNetworkStateSnapshot.DeviceSnapshot(
                    "SS01-DCB",
                    "DC breaker",
                    "DC_BREAKER",
                    "CLOSED",
                    true,
                    List.of("P01")
                ))
            )),
            List.of(new PowerNetworkStateSnapshot.ThirdRailSectionSnapshot(
                "TRS01",
                "P01",
                0,
                2500,
                "ENERGIZED",
                "AVAILABLE",
                "DOUBLE_END",
                externalVoltage,
                1_200,
                900_000,
                0,
                0,
                "test external voltage"
            )),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private VehiclePhysicsOutput output(
        String trainId,
        double positionMeters,
        double railCurrentAmps,
        double tractionPowerWatts,
        double regenPowerWatts
    ) {
        return new VehiclePhysicsOutput(
            trainId,
            positionMeters,
            10,
            0,
            tractionPowerWatts > 0 ? 100_000 : 0,
            regenPowerWatts > 0 ? 80_000 : 0,
            regenPowerWatts > 0 ? 36_000 : 0,
            tractionPowerWatts,
            railCurrentAmps,
            regenPowerWatts,
            0.1,
            regenPowerWatts / 3_600_000,
            "OK"
        );
    }
}
