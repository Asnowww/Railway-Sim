package com.railwaysim.vehicle.drivercab;

public record DriverCabPlcOutputPacket(
    boolean highVoltageClosedIndicator,
    boolean brakeReleaseFaultIndicator,
    boolean doorOpenLamp,
    boolean doorsClosedLockedLamp,
    boolean networkFaultIndicator,
    boolean automaticTurnbackAvailable,
    boolean atoModeAvailable,
    boolean washModeActive,
    boolean atoModeActive,
    boolean automaticTurnbackActive
) {
    public byte[] toBytes() {
        byte[] bytes = new byte[4];
        bytes[0] = (byte) 0x55;
        bytes[1] = (byte) 0xaa;
        bytes[2] = (byte) (
            (highVoltageClosedIndicator ? 1 : 0) << 0
            | (brakeReleaseFaultIndicator ? 1 : 0) << 1
            | (doorOpenLamp ? 1 : 0) << 2
            | (doorsClosedLockedLamp ? 1 : 0) << 3
            | (networkFaultIndicator ? 1 : 0) << 4
            | (automaticTurnbackAvailable ? 1 : 0) << 5
            | (atoModeAvailable ? 1 : 0) << 6
            | (washModeActive ? 1 : 0) << 7
        );
        bytes[3] = (byte) (
            (atoModeActive ? 1 : 0) << 0
            | (automaticTurnbackActive ? 1 : 0) << 1
        );
        return bytes;
    }
}
