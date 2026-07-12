package com.railwaysim.vehicle.drivercab;

/** @deprecated LOCAL mode only. EXTERNAL_HTTP mode uses 9300's copy. */
@Deprecated(forRemoval=true, since="2.0")
public enum DriverCabDoorModeSwitch {
    SEMI_AUTOMATIC(0),
    MANUAL(1),
    AUTOMATIC(2);

    private final int protocolCode;

    DriverCabDoorModeSwitch(int protocolCode) {
        this.protocolCode = protocolCode;
    }

    public int protocolCode() {
        return protocolCode;
    }

    public static DriverCabDoorModeSwitch fromProtocolCode(int code) {
        return switch (code) {
            case 1 -> MANUAL;
            case 2 -> AUTOMATIC;
            default -> SEMI_AUTOMATIC;
        };
    }
}
