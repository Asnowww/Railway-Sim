package com.railwaysim.simulation;

public record StoppingTarget(
    String source,
    double positionMeters,
    String stationId,
    String platformId,
    long validFromTick
) {
    public StoppingTarget {
        if (!"STATION".equals(source) && !"MOVEMENT_AUTHORITY".equals(source)) {
            throw new IllegalArgumentException("Unsupported stopping target source: " + source);
        }
        if (!Double.isFinite(positionMeters)) {
            throw new IllegalArgumentException("Stopping target position must be finite");
        }
    }
}
