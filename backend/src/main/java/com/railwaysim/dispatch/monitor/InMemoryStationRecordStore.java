package com.railwaysim.dispatch.monitor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

@Component
public class InMemoryStationRecordStore implements StationRecordStore {

    private final List<TrainStationEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void append(TrainStationEvent event) {
        events.add(event);
    }

    @Override
    public List<TrainStationEvent> list(String simulationRunId) {
        List<TrainStationEvent> filtered = new ArrayList<>();
        for (TrainStationEvent event : events) {
            if (event.simulationRunId().equals(simulationRunId)) {
                filtered.add(event);
            }
        }
        return filtered;
    }

    public void clear() {
        events.clear();
    }
}
