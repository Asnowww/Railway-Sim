package com.railwaysim.vehicle.external;

public record ExternalSegmentPosition(
    int segNo,
    double offset
) {
    public ExternalSegmentPosition {
        segNo = Math.max(1, segNo);
        offset = Math.max(0, offset);
    }
}
