package com.railwaysim.vehicleruntime.drivercab;

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
}
