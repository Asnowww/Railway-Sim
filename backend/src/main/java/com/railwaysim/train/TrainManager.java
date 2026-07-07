package com.railwaysim.train;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TrainManager {

    private final List<TrainEntity> trains = new ArrayList<>();

    @PostConstruct
    public void init() {
        reset();
    }

    public synchronized void reset() {
        trains.clear();
        trains.add(new TrainEntity("TR-001", "demo-line-1", 100, 120));
        trains.add(new TrainEntity("TR-002", "demo-line-1", 900, 120));
    }

    public synchronized List<TrainState> states() {
        return trains.stream().map(TrainEntity::state).toList();
    }
}

