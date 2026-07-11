package com.railwaysim.vehicle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VehicleLoadPolicyTests {

    @Test
    void dynamicLoadMassOverridesLegacyLoadRate() {
        double loadMassKg = VehicleLoadPolicy.loadMassKg(91_200, 0.2);

        assertThat(loadMassKg).isEqualTo(91_200);
        assertThat(VehicleLoadPolicy.loadRateFromMass(loadMassKg)).isEqualTo(1.2);
        assertThat(VehicleLoadPolicy.overloadStatus(loadMassKg)).isEqualTo("OVERLOAD");
        assertThat(VehicleLoadPolicy.vehicleProtectionReason("OVERLOAD")).isEqualTo("OVERLOAD");
    }

    @Test
    void tractionAndBrakeFactorsReflectLoadAndAvailableUnits() {
        double overloadMassKg = VehicleLoadPolicy.MAX_LOAD_MASS_KG * 1.1;

        assertThat(VehicleLoadPolicy.tractionCommandFactor(overloadMassKg, 6)).isEqualTo(0.75);
        assertThat(VehicleLoadPolicy.tractionCommandFactor(overloadMassKg, 3)).isEqualTo(0.5625);
        assertThat(VehicleLoadPolicy.brakingDecelerationFactor(overloadMassKg, 3)).isBetween(0.68, 0.69);
    }
}
