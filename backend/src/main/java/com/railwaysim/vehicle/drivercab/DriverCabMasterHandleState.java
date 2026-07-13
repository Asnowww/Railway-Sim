package com.railwaysim.vehicle.drivercab;

public enum DriverCabMasterHandleState {
    ZERO(0), TRACTION(1), BRAKE(2), FAST_BRAKE(4);
    private final int protocolCode;
    DriverCabMasterHandleState(int protocolCode) { this.protocolCode = protocolCode; }
    public int protocolCode() { return protocolCode; }
    public static DriverCabMasterHandleState fromProtocolCode(int code) {
        for (var v : values()) { if (v.protocolCode == code) return v; }
        throw new IllegalArgumentException("Unsupported masterHandleState code: " + code);
    }
}
