package com.railwaysim.vehicle.drivercab;

/** @deprecated LOCAL mode only. EXTERNAL_HTTP mode uses 9300's copy. */
@Deprecated(forRemoval=true, since="2.0")
public enum DriverCabDirectionHandleState {
    ZERO(0),
    FORWARD(1),
    BACKWARD(2);

    private final int protocolCode;

    DriverCabDirectionHandleState(int protocolCode) {
        this.protocolCode = protocolCode;
    }

    public int protocolCode() {
        return protocolCode;
    }

    public static DriverCabDirectionHandleState fromProtocolCode(int code) {
        return switch (code) {
            case 1 -> FORWARD;
            case 2 -> BACKWARD;
            default -> ZERO;
        };
    }
}
