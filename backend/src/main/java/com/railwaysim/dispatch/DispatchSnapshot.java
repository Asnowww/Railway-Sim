package com.railwaysim.dispatch;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
        String regulatedTrainId,
        String frontTrainId,
        Double headwayActualSeconds,
        Double headwayErrorSeconds,
        int headwayDeviationSeconds,
        String headwayState,
        String headwayAction,
        String regulationAction,
        String regulationReason,
        int dwellDeviationSeconds,
        int departureDelaySeconds
    ) {
    }

    public record DisturbanceView(
        String id,
        String trainId,
        String regulatedTrainId,
        String stationId,
        String disturbanceType,
        String regulationAction,
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
        String regulatedTrainId,
        String commandType,
        String status,
        String reason,
        String regulationAction
    ) {
    }

    public record RouteDecisionView(
        String decisionId,
        String selectedTrainId,
        String selectedRouteId,
        List<String> waitingTrainIds,
        Map<String, Double> priorityScores,
        String status,
        String routeCommandId,
        String reason,
        String rejectReason
    ) {
        public RouteDecisionView {
            waitingTrainIds = waitingTrainIds == null ? List.of() : List.copyOf(waitingTrainIds);
            priorityScores = priorityScores == null ? Map.of() : Map.copyOf(priorityScores);
        }
    }

    public record RouteReservationView(
        String reservationId,
        String trainId,
        String routeId,
        String decisionId,
        String state,
        String commandId,
        String rejectReason,
        int retryCount,
        Instant expiresAt
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
