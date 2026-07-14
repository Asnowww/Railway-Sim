package com.railwaysim.config;

import com.railwaysim.vehicle.drivercab.DriverCabArbitrationPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Arbitration policy for the two driver-cab control seats (browser console vs physical PLC).
 * During manual-only bring-up the front-end console is the operator seat, so the default is FRONTEND:
 * physical PLC input is mirrored for display but does not drive 9300 until switched to PHYSICAL.
 */
@ConfigurationProperties(prefix = "railway.simulation.driver-cab")
public class DriverCabControlProperties {

    private DriverCabArbitrationPolicy controlSource = DriverCabArbitrationPolicy.FRONTEND;

    public DriverCabArbitrationPolicy getControlSource() {
        return controlSource;
    }

    public void setControlSource(DriverCabArbitrationPolicy controlSource) {
        this.controlSource = controlSource == null ? DriverCabArbitrationPolicy.FRONTEND : controlSource;
    }
}
