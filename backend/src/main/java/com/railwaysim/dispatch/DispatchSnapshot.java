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
    List<RouteReservationView> routeReservations,
    List<OperationPlanView> operationPlans,
    LineRegulationPlanView lineRegulationPlan
) {
    public DispatchSnapshot {
        services = services == null ? List.of() : List.copyOf(services);
        stationHeadways = stationHeadways == null ? List.of() : List.copyOf(stationHeadways);
        routeDecisions = routeDecisions == null ? List.of() : List.copyOf(routeDecisions);
        routeReservations = routeReservations == null ? List.of() : List.copyOf(routeReservations);
        operationPlans = operationPlans == null ? List.of() : List.copyOf(operationPlans);
        lineRegulationPlan = lineRegulationPlan == null ? LineRegulationPlanView.empty() : lineRegulationPlan;
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
        String regulationAction,
        Map<String, Object> payload,
        Instant createdAt,
        Instant appliedAt
    ) {
        public CommandView {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
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

    public record OperationPlanView(
        String planId,
        String routeId,
        String routeName,
        String direction,
        String trainId,
        String originPointId,
        String destinationPointId,
        List<String> viaPointIds,
        List<String> pointIds,
        List<String> stationIds,
        List<String> segmentIds,
        Instant plannedDepartureAt,
        String status,
        int priority,
        int version,
        String routeCommandId,
        String rejectReason
    ) {
        public OperationPlanView {
            viaPointIds = viaPointIds == null ? List.of() : List.copyOf(viaPointIds);
            pointIds = pointIds == null ? List.of() : List.copyOf(pointIds);
            stationIds = stationIds == null ? List.of() : List.copyOf(stationIds);
            segmentIds = segmentIds == null ? List.of() : List.copyOf(segmentIds);
        }
    }

    public record LineRegulationPlanView(
        String planId,
        Instant generatedAt,
        String objective,
        String status,
        int targetHeadwaySec,
        Double currentMaxAbsHeadwayErrorSec,
        Double predictedMaxAbsHeadwayErrorSec,
        int commandCount,
        List<LineRegulationDecisionView> decisions
    ) {
        public LineRegulationPlanView {
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
        }

        public static LineRegulationPlanView empty() {
            return new LineRegulationPlanView(
                "",
                null,
                "RESTORE_EVEN_HEADWAY",
                "NO_DATA",
                300,
                null,
                null,
                0,
                List.of()
            );
        }
    }

    public record LineRegulationDecisionView(
        String trainId,
        String regulatedTrainId,
        String frontTrainId,
        String action,
        String commandType,
        String status,
        String reason,
        Double currentHeadwaySec,
        int targetHeadwaySec,
        Double currentHeadwayErrorSec,
        Double predictedHeadwayErrorSec,
        double priorityScore,
        String signalConstraint,
        String commandId
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
            List.of(),
            List.of(),
            LineRegulationPlanView.empty()
        );
    }
}
