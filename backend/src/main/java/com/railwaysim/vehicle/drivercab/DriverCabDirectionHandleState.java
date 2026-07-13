package com.railwaysim.vehicle.drivercab;

public enum DriverCabDirectionHandleState {
    ZERO(0), FORWARD(1), BACKWARD(2);
    private final int protocolCode;
    DriverCabDirectionHandleState(int protocolCode) { this.protocolCode = protocolCode; }
    public int protocolCode() { return protocolCode; }
    public static DriverCabDirectionHandleState fromProtocolCode(int code) {
        for (var v : values()) { if (v.protocolCode == code) return v; }
        throw new IllegalArgumentException("Unsupported directionHandleState code: " + code);
    }
}
