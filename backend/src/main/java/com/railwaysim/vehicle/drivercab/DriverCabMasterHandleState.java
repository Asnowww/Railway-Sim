package com.railwaysim.vehicle.drivercab;

public enum DriverCabMasterHandleState {
    ZERO(0),
    TRACTION(1),
    BRAKE(2),
    FAST_BRAKE(4);

    private final int protocolCode;

    DriverCabMasterHandleState(int protocolCode) {
        this.protocolCode = protocolCode;
    }

    public int protocolCode() {
        return protocolCode;
    }

    public static DriverCabMasterHandleState fromProtocolCode(int code) {
        return switch (code) {
            case 1 -> TRACTION;
            case 2 -> BRAKE;
            case 4 -> FAST_BRAKE;
            default -> ZERO;
        };
    }
}
