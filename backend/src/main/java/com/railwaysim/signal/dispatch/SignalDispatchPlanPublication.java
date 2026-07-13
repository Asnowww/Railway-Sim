package com.railwaysim.signal.dispatch;

import java.time.Instant;
import java.util.List;

public record SignalDispatchPlanPublication(
    String publicationId,
    String simulationRunId,
    String dispatchPlanId,
    String lineId,
    Instant effectiveFrom,
    Instant publishedAt,
    String operator,
    String status,
    int acceptedCount,
    int rejectedCount,
    List<Entry> entries
) {
    public SignalDispatchPlanPublication {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public record Entry(
        String entryId,
        String sourceType,
        String sourceId,
        String trainId,
        String routeId,
        String routeName,
        String direction,
        String originPointId,
        String destinationPointId,
        List<String> viaPointIds,
        List<String> stationIds,
        List<String> segmentIds,
        Instant plannedDepartureAt,
        String status,
        String rejectReason
    ) {
        public Entry {
            viaPointIds = viaPointIds == null ? List.of() : List.copyOf(viaPointIds);
            stationIds = stationIds == null ? List.of() : List.copyOf(stationIds);
            segmentIds = segmentIds == null ? List.of() : List.copyOf(segmentIds);
        }
    }
}
