package com.railwaysim.signal;

public record MovementAuthority(
    String trainId,
    double authorityEndMeters,
    double speedLimitMetersPerSecond,
    String reason,
    String currentSegmentId
) {
}

