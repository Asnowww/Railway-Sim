package com.railwaysim.localnet.drivercab;

import com.railwaysim.config.LocalNetProperties;

public record DriverCabConnectionConfig(
    String trainId,
    DriverCabRole role,
    String host,
    int port,
    long cycleMillis,
    long timeoutMillis
) {
    public static DriverCabConnectionConfig from(LocalNetProperties.DriverCabConnection source) {
        return new DriverCabConnectionConfig(
            source.getTrainId(),
            DriverCabRole.from(source.getRole()),
            source.getHost(),
            source.getPort(),
            source.getCycleMillis(),
            source.getTimeoutMillis()
        );
    }

    public boolean configured() {
        return port > 0 && host != null && !host.isBlank();
    }
}
