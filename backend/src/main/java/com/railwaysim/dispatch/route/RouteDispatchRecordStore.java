package com.railwaysim.dispatch.route;

import com.railwaysim.dispatch.DispatchCommand;
import com.railwaysim.dispatch.DispatchCommandFeedback;
import com.railwaysim.dispatch.command.CommandStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RouteDispatchRecordStore {

    private final Map<String, RouteDispatchDecision> decisionsById = new LinkedHashMap<>();
    private final Map<String, RouteReservation> reservationsById = new LinkedHashMap<>();
    private final Map<String, String> reservationIdByCommandId = new HashMap<>();
    private final Map<String, String> decisionIdByCommandId = new HashMap<>();

    public synchronized void clear() {
        decisionsById.clear();
        reservationsById.clear();
        reservationIdByCommandId.clear();
        decisionIdByCommandId.clear();
    }

    public synchronized DispatchCommand trackSubmittedRouteCommand(String simulationRunId, DispatchCommand command) {
        if (!isRouteCommand(command)) {
            return command;
        }

        Instant now = command.createdAt() == null ? Instant.now() : command.createdAt();
        Map<String, Object> payload = command.payload() == null
            ? new HashMap<>()
            : new HashMap<>(command.payload());
        String routeId = routeIdFrom(command, payload);
        String decisionId = payloadString(payload, "decisionId");
        if (decisionId == null || decisionId.isBlank()) {
            decisionId = "RD-" + UUID.randomUUID().toString().substring(0, 8);
            payload.put("decisionId", decisionId);
        }
        String reservationId = payloadString(payload, "reservationId");
        if (reservationId == null || reservationId.isBlank()) {
            reservationId = "RR-" + UUID.randomUUID().toString().substring(0, 8);
            payload.put("reservationId", reservationId);
        }
        if (routeId != null && !routeId.isBlank()) {
            payload.put("routeId", routeId);
        }

        String rejectReason = payloadString(payload, "skipReason");
        boolean rejected = CommandStatus.SKIPPED.equals(command.status());
        String decisionStatus = rejected ? RouteDecisionStatus.REJECTED : RouteDecisionStatus.REQUESTED;
        String reservationState = rejected ? RouteReservationState.REJECTED : RouteReservationState.REQUESTED;

        RouteDispatchDecision decision = new RouteDispatchDecision(
            decisionId,
            simulationRunId,
            command.trainId(),
            routeId,
            List.of(),
            Map.of(),
            command.reason(),
            decisionStatus,
            command.id(),
            List.of(),
            rejected ? rejectReason : null,
            now,
            now
        );
        RouteReservation reservation = new RouteReservation(
            reservationId,
            simulationRunId,
            command.trainId(),
            routeId,
            decisionId,
            reservationState,
            command.id(),
            rejected ? rejectReason : null,
            0,
            rejected ? null : now,
            null,
            null,
            null,
            now
        );

        decisionsById.put(decisionId, decision);
        reservationsById.put(reservationId, reservation);
        decisionIdByCommandId.put(command.id(), decisionId);
        reservationIdByCommandId.put(command.id(), reservationId);

        return new DispatchCommand(
            command.id(),
            command.trainId(),
            command.commandType(),
            payload,
            command.reason(),
            command.status(),
            command.createdAt(),
            command.appliedAt()
        );
    }

    public synchronized void markCommandSent(DispatchCommand command) {
        if (!isRouteCommand(command)) {
            return;
        }
        if (!decisionIdByCommandId.containsKey(command.id())) {
            trackSubmittedRouteCommand(
                payloadString(command.payload(), "simulationRunId"),
                command
            );
        }
    }

    public synchronized void updateFromFeedback(DispatchCommand command, DispatchCommandFeedback feedback) {
        if (!isRouteCommand(command)) {
            return;
        }
        String decisionId = decisionIdByCommandId.get(command.id());
        String reservationId = reservationIdByCommandId.get(command.id());
        if (decisionId == null || reservationId == null) {
            trackSubmittedRouteCommand(payloadString(command.payload(), "simulationRunId"), command);
            decisionId = decisionIdByCommandId.get(command.id());
            reservationId = reservationIdByCommandId.get(command.id());
        }
        if (decisionId == null || reservationId == null) {
            return;
        }

        Instant now = feedback.feedbackAt() == null ? Instant.now() : feedback.feedbackAt();
        boolean accepted = CommandStatus.EFFECT_CONFIRMED.equals(feedback.feedbackStatus())
            || CommandStatus.COMPLETED.equals(feedback.feedbackStatus());
        boolean rejected = CommandStatus.SKIPPED.equals(feedback.feedbackStatus())
            || CommandStatus.CANCELLED.equals(feedback.feedbackStatus());
        if (!accepted && !rejected) {
            return;
        }

        String decisionStatus = accepted ? RouteDecisionStatus.ACCEPTED
            : CommandStatus.CANCELLED.equals(feedback.feedbackStatus())
                ? RouteDecisionStatus.CANCELLED
                : RouteDecisionStatus.REJECTED;
        String reservationState = accepted ? RouteReservationState.ACCEPTED
            : CommandStatus.CANCELLED.equals(feedback.feedbackStatus())
                ? RouteReservationState.CANCELLED
                : RouteReservationState.REJECTED;
        String rejectReason = accepted ? null : feedback.reason();

        RouteDispatchDecision decision = decisionsById.get(decisionId);
        if (decision != null) {
            decisionsById.put(decisionId, new RouteDispatchDecision(
                decision.decisionId(),
                decision.simulationRunId(),
                decision.selectedTrainId(),
                decision.selectedRouteId(),
                decision.waitingTrainIds(),
                decision.priorityScores(),
                decision.reason(),
                decisionStatus,
                decision.routeCommandId(),
                decision.waitingCommandIds(),
                rejectReason,
                decision.createdAt(),
                now
            ));
        }

        RouteReservation reservation = reservationsById.get(reservationId);
        if (reservation != null) {
            reservationsById.put(reservationId, new RouteReservation(
                reservation.reservationId(),
                reservation.simulationRunId(),
                reservation.trainId(),
                reservation.routeId(),
                reservation.decisionId(),
                reservationState,
                reservation.commandId(),
                rejectReason,
                reservation.retryCount(),
                reservation.requestedAt(),
                accepted ? now : reservation.acceptedAt(),
                reservation.releasedAt(),
                reservation.expiresAt(),
                now
            ));
        }
    }

    public synchronized void cancelCommand(DispatchCommand command, Instant cancelledAt) {
        if (!isRouteCommand(command)) {
            return;
        }
        DispatchCommandFeedback feedback = new DispatchCommandFeedback(
            command.id(),
            command.trainId(),
            command.commandType(),
            "DISPATCH",
            CommandStatus.CANCELLED,
            "command cancelled",
            cancelledAt == null ? Instant.now() : cancelledAt,
            Map.of()
        );
        updateFromFeedback(command, feedback);
    }

    public synchronized List<RouteDispatchDecision> listDecisions(String simulationRunId) {
        return decisionsById.values().stream()
            .filter(decision -> simulationRunId == null || simulationRunId.equals(decision.simulationRunId()))
            .toList();
    }

    public synchronized List<RouteReservation> listReservations(String simulationRunId) {
        return reservationsById.values().stream()
            .filter(reservation -> simulationRunId == null || simulationRunId.equals(reservation.simulationRunId()))
            .toList();
    }

    public synchronized List<RouteDispatchDecision> visibleDecisions(String simulationRunId, int limit) {
        List<RouteDispatchDecision> decisions = new ArrayList<>(listDecisions(simulationRunId));
        if (limit <= 0 || decisions.size() <= limit) {
            return List.copyOf(decisions);
        }
        return decisions.subList(decisions.size() - limit, decisions.size());
    }

    public synchronized List<RouteReservation> visibleReservations(String simulationRunId, int limit) {
        List<RouteReservation> reservations = new ArrayList<>(listReservations(simulationRunId));
        if (limit <= 0 || reservations.size() <= limit) {
            return List.copyOf(reservations);
        }
        return reservations.subList(reservations.size() - limit, reservations.size());
    }

    public static boolean isRouteCommand(DispatchCommand command) {
        return command != null && isRouteCommand(command.commandType());
    }

    public static boolean isRouteCommand(String commandType) {
        return "REQUEST_ROUTE".equals(commandType) || "REROUTE".equals(commandType);
    }

    public static String routeIdFrom(DispatchCommand command) {
        return routeIdFrom(command, command == null ? null : command.payload());
    }

    public static String routeIdFrom(DispatchCommand command, Map<String, Object> payload) {
        String routeId = payloadString(payload, "routeId");
        if (routeId != null && !routeId.isBlank()) {
            return routeId;
        }
        String detail = payloadString(payload, "detail");
        if (detail != null && !detail.isBlank()) {
            return detail;
        }
        return null;
    }

    private static String payloadString(Map<String, Object> payload, String key) {
        if (payload == null || key == null) {
            return null;
        }
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }
}
