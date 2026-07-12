package com.railwaysim.dispatch;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DispatchSnapshot(
    String runMode,
    String planId,
    int targetHeadwaySeconds,
    int defaultDwellSeconds,
    List<ServicePlanView> services,
    List<StationHeadwayView> stationHeadways,
    boolean interventionActive,
    List<TrainProfileView> trainProfiles,
    List<DisturbanceView> openDisturbances,
    List<CommandView> activeCommands,
    boolean routeDispatchActive,
    List<RouteDecisionView> routeDecisions,
    List<RouteReservationView> routeReservations
) {
    public DispatchSnapshot {
        services = services == null ? List.of() : List.copyOf(services);
        stationHeadways = stationHeadways == null ? List.of() : List.copyOf(stationHeadways);
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

    public record ServicePlanView(
        String serviceId,
        String circulationId,
        String trainId,
        String originStationId,
        String terminusStationId,
        Instant plannedDepartureAt,
        String departureStatus,
        String departureCommandId
    ) {
    }

    public record StationHeadwayView(
        String stationId,
        String direction,
        String trainId,
        String frontTrainId,
        Instant departureAt,
        int targetHeadwaySeconds,
        double actualHeadwaySeconds,
        double headwayErrorSeconds,
        String state,
        String regulationAction
    ) {
    }

    public record RouteDecisionView(
        String decisionId,
        String selectedTrainId,
        String selectedRouteId,
        List<String> waitingTrainIds,
        Map<String, Double> priorityScores,
        double waitingSeconds,
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
        String failureCode,
        String failureCategory,
        boolean retryable,
        int retryCount,
        Instant expiresAt,
        Instant nextRetryAt,
        Instant timedOutAt,
        String cancelCommandId
    ) {
    }

    public static DispatchSnapshot empty() {
        return new DispatchSnapshot(
            "FLAT",
            "",
            300,
            25,
            List.of(),
            List.of(),
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
