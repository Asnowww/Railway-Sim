package com.railwaysim.dispatch.plan;

import java.util.List;

public record DispatchPlanFile(
    String planId,
    String lineId,
    List<PeriodEntry> periods,
    List<StationEntry> stations,
    List<SegmentEntry> segments,
    List<CirculationEntry> circulations,
    List<ServiceEntry> services
) {
    public DispatchPlanFile {
        periods = periods == null ? List.of() : List.copyOf(periods);
        stations = stations == null ? List.of() : List.copyOf(stations);
        segments = segments == null ? List.of() : List.copyOf(segments);
        circulations = circulations == null ? List.of() : List.copyOf(circulations);
        services = services == null ? List.of() : List.copyOf(services);
    }

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

    public record CirculationEntry(
        String id,
        String rollingStockId,
        List<String> serviceIds
    ) {
        public CirculationEntry {
            serviceIds = serviceIds == null ? List.of() : List.copyOf(serviceIds);
        }
    }

    public record ServiceEntry(
        String serviceId,
        String circulationId,
        String trainId,
        int trainNo,
        int linkId,
        double offsetMeters,
        String direction,
        List<StopTimeEntry> stops
    ) {
        public ServiceEntry {
            stops = stops == null ? List.of() : List.copyOf(stops);
        }
    }

    public record StopTimeEntry(
        String stationId,
        int arrivalOffsetSec,
        int departureOffsetSec
    ) {
    }
}
