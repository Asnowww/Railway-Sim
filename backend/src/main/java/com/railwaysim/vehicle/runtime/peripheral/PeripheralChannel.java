package com.railwaysim.vehicle.runtime.peripheral;

public enum PeripheralChannel {
    PLC_INPUT(1),
    PLC_OUTPUT(2),
    NETWORK_SCREEN_INPUT(3),
    NETWORK_SCREEN_OUTPUT(4),
    SIGNAL_SCREEN_OUTPUT(5),
    VISION_UDP_OUTPUT(6);

    private final int code;

    PeripheralChannel(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static PeripheralChannel fromCode(int code) {
        for (PeripheralChannel channel : values()) {
            if (channel.code == code) {
                return channel;
            }
        }
        throw new IllegalArgumentException("Unsupported peripheral channel code: " + code);
    }
}
