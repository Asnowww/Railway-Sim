package com.railwaysim.vehicleruntime.drivercab;

public enum DriverCabDoorModeSwitch {
    AUTOMATIC(0), SEMI_AUTOMATIC(1), MANUAL(2);

    private final int protocolCode;

    DriverCabDoorModeSwitch(int protocolCode) { this.protocolCode = protocolCode; }

    public int protocolCode() { return protocolCode; }

    public static DriverCabDoorModeSwitch fromProtocolCode(int code) {
        for (var v : values()) { if (v.protocolCode == code) return v; }
        throw new IllegalArgumentException("Unsupported doorModeSwitch code: " + code);
    }
}
