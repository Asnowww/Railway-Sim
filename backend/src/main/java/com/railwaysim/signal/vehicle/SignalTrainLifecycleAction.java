package com.railwaysim.signal.vehicle;

public enum SignalTrainLifecycleAction {
    ADD(0x01),
    DELETE(0x02),
    CLEAR(0x04);

    private final int protocolCode;

    SignalTrainLifecycleAction(int protocolCode) {
        this.protocolCode = protocolCode;
    }

    public int protocolCode() {
        return protocolCode;
    }

    public static SignalTrainLifecycleAction fromProtocolCode(int protocolCode) {
        for (SignalTrainLifecycleAction action : values()) {
            if (action.protocolCode == protocolCode) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unsupported signal train lifecycle action: " + protocolCode);
    }
}
