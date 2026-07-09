package com.railwaysim.dispatch.disturbance;

import java.util.List;

public interface DisturbanceRecordStore {

    void save(DisturbanceEvent event);

    void update(DisturbanceEvent event);

    List<DisturbanceEvent> list(String simulationRunId);
}
