package com.railwaysim.dispatch.monitor;

import java.util.List;

public interface StationRecordStore {

    void append(TrainStationEvent event);

    List<TrainStationEvent> list(String simulationRunId);
}
