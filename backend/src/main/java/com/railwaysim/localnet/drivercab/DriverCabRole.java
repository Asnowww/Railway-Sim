package com.railwaysim.localnet.drivercab;

public enum DriverCabRole {
    PLC,
    NETWORK_SCREEN,
    SIGNAL_SCREEN;

    public static DriverCabRole from(String value) {
        if (value == null || value.isBlank()) {
            return PLC;
        }
        String normalized = value.trim().replace('-', '_').toUpperCase();
        return switch (normalized) {
            case "NETWORK", "NETWORK_SCREEN", "NET_SCREEN" -> NETWORK_SCREEN;
            case "SIGNAL", "SIGNAL_SCREEN", "MMI" -> SIGNAL_SCREEN;
            default -> PLC;
        };
    }
}
