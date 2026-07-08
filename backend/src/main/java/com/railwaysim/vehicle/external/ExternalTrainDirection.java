package com.railwaysim.vehicle.external;

public enum ExternalTrainDirection {
    UP(0x55),
    DOWN(0xaa),
    UNKNOWN(0xff);

    private final int protocolCode;

    ExternalTrainDirection(int protocolCode) {
        this.protocolCode = protocolCode;
    }

    public int protocolCode() {
        return protocolCode;
    }

    public static ExternalTrainDirection fromProtocolCode(int protocolCode) {
        for (ExternalTrainDirection direction : values()) {
            if (direction.protocolCode == protocolCode) {
                return direction;
            }
        }
        return UNKNOWN;
    }
}
