package com.railwaysim.vehicle.drivercab;

/**
 * Which control seat is authoritative when both the browser console and the physical PLC send input
 * for the same train. Non-authoritative input still updates the display snapshot but is not forwarded
 * to the 9300 vehicle runtime.
 */
public enum DriverCabArbitrationPolicy {
    /** Only browser-console input drives 9300; physical PLC input is display-only. (联调默认) */
    FRONTEND,
    /** Only physical PLC input drives 9300; browser-console input is display-only. */
    PHYSICAL,
    /** Both seats drive 9300; the most recent input wins (9300 queue overwrites). */
    LAST_WINS;

    public boolean forwards(DriverCabControlSource source) {
        return switch (this) {
            case LAST_WINS -> true;
            case FRONTEND -> source == DriverCabControlSource.FRONTEND;
            case PHYSICAL -> source == DriverCabControlSource.PHYSICAL;
        };
    }
}
