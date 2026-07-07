package com.railwaysim.simulation.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.stereotype.Component;

@Component
public class SimpleEventBus {

    private final Queue<DomainEvent> events = new ConcurrentLinkedQueue<>();

    public void publish(DomainEvent event) {
        events.add(event);
    }

    public List<DomainEvent> drain() {
        List<DomainEvent> drained = new ArrayList<>();
        DomainEvent event;
        while ((event = events.poll()) != null) {
            drained.add(event);
        }
        return drained;
    }
}

