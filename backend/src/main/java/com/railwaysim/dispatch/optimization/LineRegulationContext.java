package com.railwaysim.dispatch.optimization;

import com.railwaysim.dispatch.DispatchCommand;
import com.railwaysim.dispatch.disturbance.DisturbanceEvent;
import com.railwaysim.dispatch.monitor.TrainRunProfile;
import com.railwaysim.dispatch.plan.CurrentRunPlan;
import com.railwaysim.dispatch.route.RouteReservation;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.train.TrainState;
import java.time.Instant;
import java.util.List;

public record LineRegulationContext(
    String simulationRunId,
    Instant simulatedAt,
    CurrentRunPlan currentPlan,
    List<TrainRunProfile> profiles,
    List<TrainState> trains,
    List<MovementAuthority> authorities,
    List<DisturbanceEvent> openDisturbances,
    List<DispatchCommand> activeCommands,
    List<RouteReservation> routeReservations
) {
    public LineRegulationContext {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        trains = trains == null ? List.of() : List.copyOf(trains);
        authorities = authorities == null ? List.of() : List.copyOf(authorities);
        openDisturbances = openDisturbances == null ? List.of() : List.copyOf(openDisturbances);
        activeCommands = activeCommands == null ? List.of() : List.copyOf(activeCommands);
        routeReservations = routeReservations == null ? List.of() : List.copyOf(routeReservations);
    }
}
