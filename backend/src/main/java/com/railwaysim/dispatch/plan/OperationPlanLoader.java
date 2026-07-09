package com.railwaysim.dispatch.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.railwaysim.dispatch.config.DispatchProperties;
import com.railwaysim.dispatch.monitor.StationInfo;
import com.railwaysim.dispatch.plan.DispatchPlanFile.SegmentEntry;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@Component
public class OperationPlanLoader {

    private static final Map<String, Integer> PERIOD_PRIORITY = Map.of(
        "PEAK", 3,
        "FLAT", 2,
        "OFF_PEAK", 1
    );

    private final DispatchProperties properties;
    private final ResourceLoader resourceLoader;
    private String planId;
    private String lineId;
    private List<RunModePeriod> periods = List.of();
    private List<StationInfo> stations = List.of();
    private List<SegmentEntry> segments = List.of();

    public OperationPlanLoader(DispatchProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void load() throws IOException {
        Resource resource = resourceLoader.getResource(properties.getPlanLocation());
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        DispatchPlanFile file;
        try (InputStream inputStream = resource.getInputStream()) {
            file = mapper.readValue(inputStream, DispatchPlanFile.class);
        }
        planId = file.planId();
        lineId = file.lineId();
        periods = file.periods().stream()
            .map(entry -> new RunModePeriod(
                entry.type(),
                LocalTime.parse(entry.start()),
                LocalTime.parse(entry.end()),
                entry.intervalSec(),
                entry.dwellSec()
            ))
            .toList();
        stations = file.stations().stream()
            .map(entry -> new StationInfo(entry.id(), entry.positionMeters(), entry.platformCapacity()))
            .sorted(Comparator.comparingDouble(StationInfo::positionMeters))
            .toList();
        segments = file.segments().stream()
            .sorted(Comparator.comparingDouble(SegmentEntry::startMeters))
            .toList();
    }

    public CurrentRunPlan resolve(Instant simulatedTime) {
        LocalTime time = LocalTime.ofInstant(simulatedTime, ZoneId.systemDefault());
        RunModePeriod matched = periods.stream()
            .filter(period -> contains(period, time))
            .max(Comparator.comparingInt(period -> PERIOD_PRIORITY.getOrDefault(period.periodType(), 0)))
            .orElse(fallbackPeriod());
        return new CurrentRunPlan(
            planId,
            lineId,
            matched.periodType(),
            matched.departureIntervalSec(),
            matched.defaultDwellTimeSec(),
            simulatedTime
        );
    }

    public List<StationInfo> stations() {
        return stations;
    }

    public List<SegmentEntry> segments() {
        return segments;
    }

    public String planId() {
        return planId;
    }

    public String lineId() {
        return lineId;
    }

    public List<RunModePeriod> periods() {
        return periods;
    }

    private RunModePeriod fallbackPeriod() {
        return periods.stream()
            .filter(period -> "FLAT".equals(period.periodType()))
            .findFirst()
            .orElse(periods.getFirst());
    }

    private boolean contains(RunModePeriod period, LocalTime time) {
        if (period.startTime().equals(period.endTime())) {
            return true;
        }
        if (period.startTime().isBefore(period.endTime())) {
            return !time.isBefore(period.startTime()) && time.isBefore(period.endTime());
        }
        return !time.isBefore(period.startTime()) || time.isBefore(period.endTime());
    }
}
