package com.railwaysim.dispatch;

import java.util.List;

public record DispatchSnapshot(
    String runMode,
    String planId,
    int targetHeadwaySeconds,
    int defaultDwellSeconds,
    boolean interventionActive,
    List<TrainProfileView> trainProfiles,
    List<DisturbanceView> openDisturbances,
    List<CommandView> activeCommands,
    boolean routeDispatchActive,
    List<RouteDecisionView> routeDecisions,
    List<RouteReservationView> routeReservations
) {
    public DispatchSnapshot {
        routeDecisions = routeDecisions == null ? List.of() : List.copyOf(routeDecisions);
        routeReservations = routeReservations == null ? List.of() : List.copyOf(routeReservations);
    }

    public record TrainProfileView(
        String trainId,
        String frontTrainId,
        Double headwayActualSeconds,
        int headwayDeviationSeconds,
        String headwayState,
        String headwayAction,
        int dwellDeviationSeconds,
        int departureDelaySeconds
    ) {
    }

    public record DisturbanceView(
        String id,
        String trainId,
        String stationId,
        String disturbanceType,
        double deviationValue,
        String headwayDirection,
        Double targetHeadwaySec,
        Double actualHeadwaySec,
        Double toleranceSec,
        Double violationSec,
        String status
    ) {
    }

    public record CommandView(
        String id,
        String trainId,
        String commandType,
        String status,
        String reason
    ) {
    }

    public record RouteDecisionView(
        String decisionId,
        String selectedTrainId,
        String selectedRouteId,
        List<String> waitingTrainIds,
        String status,
        String routeCommandId,
        String reason,
        String rejectReason
    ) {
        public RouteDecisionView {
            waitingTrainIds = waitingTrainIds == null ? List.of() : List.copyOf(waitingTrainIds);
        }
    }

    public record RouteReservationView(
        String reservationId,
        String trainId,
        String routeId,
        String decisionId,
        String state,
        String commandId,
        String rejectReason
    ) {
    }

    public static DispatchSnapshot empty() {
        return new DispatchSnapshot(
            "FLAT",
            "",
            300,
            25,
            false,
            List.of(),
            List.of(),
            List.of(),
            false,
            List.of(),
            List.of()
        );
    }
}
