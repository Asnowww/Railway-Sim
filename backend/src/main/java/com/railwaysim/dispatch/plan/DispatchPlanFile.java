package com.railwaysim.dispatch.plan;

import java.util.List;

public record DispatchPlanFile(
    String planId,
    String lineId,
    List<PeriodEntry> periods,
    List<StationEntry> stations,
    List<SegmentEntry> segments
) {
    public record PeriodEntry(
        String type,
        String start,
        String end,
        int intervalSec,
        int dwellSec
    ) {
    }

    public record StationEntry(
        String id,
        double positionMeters,
        int platformCapacity
    ) {
    }

    public record SegmentEntry(
        String id,
        double startMeters,
        double endMeters,
        double speedLimitMps
    ) {
    }
}
