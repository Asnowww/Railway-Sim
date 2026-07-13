package com.railwaysim.vehicleruntime.protocol;

public record PeripheralFrame(PeripheralChannel channel, int sequence, int flags, String trainId, byte[] payload) {
    public PeripheralFrame {
        if (channel == null) throw new IllegalArgumentException("peripheral channel is required");
        trainId = trainId == null ? "" : trainId.trim();
        payload = payload == null ? new byte[0] : payload.clone();
    }

    @Override
    public byte[] payload() { return payload.clone(); }
}
