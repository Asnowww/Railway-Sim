package com.railwaysim.monitor;

import com.railwaysim.power.PowerSectionState;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.simulation.SimulationSnapshot;
import com.railwaysim.simulation.SimulationStatus;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.train.TrainState;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MonitorService {

    public SimulationSnapshot buildSnapshot(
        long tick,
        Instant simulatedTime,
        SimulationStatus status,
        List<TrainState> trains,
        List<TrackSegmentState> trackSegments,
        List<MovementAuthority> authorities,
        List<PowerSectionState> powerSections
    ) {
        return new SimulationSnapshot(
            tick,
            simulatedTime,
            status,
            trains,
            trackSegments,
            authorities,
            powerSections,
            List.of()
        );
    }
}

