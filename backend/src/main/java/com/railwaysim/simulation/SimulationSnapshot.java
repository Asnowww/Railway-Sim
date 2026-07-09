package com.railwaysim.simulation;

import com.railwaysim.dispatch.DispatchSnapshot;
import com.railwaysim.monitor.Alarm;
import com.railwaysim.power.PowerSectionState;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.signal.RouteState;
import com.railwaysim.signal.SignalState;
import com.railwaysim.track.SwitchState;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.train.TrainState;
import java.time.Instant;
import java.util.List;

public record SimulationSnapshot(
    long tick,
    Instant simulatedTime,
    SimulationStatus status,
    List<TrainState> trains,
    List<TrackSegmentState> trackSegments,
    List<MovementAuthority> authorities,
    List<SignalState> signalStates,
    List<SwitchState> switchStates,
    List<RouteState> routeStates,
    List<PowerSectionState> powerSections,
    List<Alarm> alarms,
    DispatchSnapshot dispatch
) {
}

