package com.railwaysim.signal.vision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class VisionVehicleStateStore {

    private final Map<String, VisionVehicleState> states = new LinkedHashMap<>();

    public synchronized VisionVehicleState put(String trainId, VisionVehicleStateRequest request) {
        VisionVehicleState state = VisionVehicleState.from(trainId, request);
        states.put(state.trainId(), state);
        return state;
    }

    public synchronized Optional<VisionVehicleState> find(String trainId) {
        return Optional.ofNullable(states.get(trainId));
    }

    public synchronized List<VisionVehicleState> states() {
        return List.copyOf(states.values());
    }

    public synchronized void clear() {
        states.clear();
    }
}
