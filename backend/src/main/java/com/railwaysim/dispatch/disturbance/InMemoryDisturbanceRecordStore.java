package com.railwaysim.dispatch.disturbance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

@Component
public class InMemoryDisturbanceRecordStore implements DisturbanceRecordStore {

    private final List<DisturbanceEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void save(DisturbanceEvent event) {
        events.add(event);
    }

    @Override
    public void update(DisturbanceEvent event) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).id().equals(event.id())) {
                events.set(i, event);
                return;
            }
        }
        events.add(event);
    }

    @Override
    public List<DisturbanceEvent> list(String simulationRunId) {
        List<DisturbanceEvent> filtered = new ArrayList<>();
        for (DisturbanceEvent event : events) {
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
