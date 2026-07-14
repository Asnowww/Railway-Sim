package com.railwaysim.vehicle.drivercab;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DriverCabArbitrationPolicyTests {

    @Test
    void frontendPolicyForwardsOnlyFrontendInput() {
        assertThat(DriverCabArbitrationPolicy.FRONTEND.forwards(DriverCabControlSource.FRONTEND)).isTrue();
        assertThat(DriverCabArbitrationPolicy.FRONTEND.forwards(DriverCabControlSource.PHYSICAL)).isFalse();
    }

    @Test
    void physicalPolicyForwardsOnlyPhysicalInput() {
        assertThat(DriverCabArbitrationPolicy.PHYSICAL.forwards(DriverCabControlSource.PHYSICAL)).isTrue();
        assertThat(DriverCabArbitrationPolicy.PHYSICAL.forwards(DriverCabControlSource.FRONTEND)).isFalse();
    }

    @Test
    void lastWinsPolicyForwardsBothSeats() {
        assertThat(DriverCabArbitrationPolicy.LAST_WINS.forwards(DriverCabControlSource.FRONTEND)).isTrue();
        assertThat(DriverCabArbitrationPolicy.LAST_WINS.forwards(DriverCabControlSource.PHYSICAL)).isTrue();
    }
}
