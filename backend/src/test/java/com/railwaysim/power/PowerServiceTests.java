package com.railwaysim.power;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.infrastructure.PowerConfigLoader;
import com.railwaysim.infrastructure.SpreadsheetLineDataLoader;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.infrastructure.YamlLineDataLoader;
import com.railwaysim.simulation.RealtimeStateCache;
import com.railwaysim.simulation.event.PowerFaultStateChangedEvent;
import com.railwaysim.simulation.event.RegenerativeEnergyAbsorbedEvent;
import com.railwaysim.simulation.event.SimpleEventBus;
import com.railwaysim.train.TrainEntity;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.util.List;
import org.junit.jupiter.api.Test;

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

    private PowerService powerService(SimpleEventBus eventBus) {
        SimulationProperties properties = new SimulationProperties();
        properties.setLineDataPath("../config/line-demo.yaml");
        properties.setPowerConfigPath("../config/power_third_rail.yaml");
        StaticInfrastructureCatalog catalog = new StaticInfrastructureCatalog(
            properties,
            new SpreadsheetLineDataLoader(),
            new YamlLineDataLoader(),
            new PowerConfigLoader()
        );
        return new PowerService(catalog, new RealtimeStateCache(), eventBus);
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
