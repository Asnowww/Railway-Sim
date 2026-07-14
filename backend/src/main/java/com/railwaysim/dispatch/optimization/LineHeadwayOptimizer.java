package com.railwaysim.dispatch.optimization;

import com.railwaysim.dispatch.DispatchCommand;
import com.railwaysim.dispatch.command.CommandStatus;
import com.railwaysim.dispatch.disturbance.DisturbanceEvent;
import com.railwaysim.dispatch.monitor.TrainRunProfile;
import com.railwaysim.dispatch.route.RouteReservation;
import com.railwaysim.dispatch.route.RouteReservationState;
import com.railwaysim.dispatch.strategy.TrainRegulationAction;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.train.TrainState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class LineHeadwayOptimizer {

    private static final int MAX_COMMANDS_PER_ROUND = 2;

    public LineHeadwayOptimizationResult optimize(LineRegulationContext context) {
        String planId = "LRP-" + UUID.randomUUID().toString().substring(0, 8);
        int planTargetHeadway = context.currentPlan().departureIntervalSec();
        if (context.profiles().isEmpty()) {
            return new LineHeadwayOptimizationResult(
                LineRegulationPlan.empty(planId, context.simulatedAt(), planTargetHeadway),
                List.of()
            );
        }

        Map<String, TrainState> trainById = mapTrains(context.trains());
        Map<String, MovementAuthority> authorityByTrain = mapAuthorities(context.authorities());
        Map<String, DisturbanceEvent> eventByTrain = mapRegulationEvents(context.openDisturbances());
        Set<String> trainsWithActiveTimeCommand = activeTimeCommandTrains(context.activeCommands());
        Map<String, RouteReservation> reservationByTrain = latestReservationByTrain(context.routeReservations());

        List<Candidate> candidates = context.profiles().stream()
            .map(profile -> candidateFor(profile, eventByTrain.get(profile.trainId()), planTargetHeadway))
            .filter(candidate -> candidate.priorityScore() > 0)
            .sorted(Comparator.comparingDouble(Candidate::priorityScore).reversed())
            .toList();

        List<LineRegulationDecision> decisions = new ArrayList<>();
        List<DispatchCommand> commands = new ArrayList<>();
        Set<String> commandedTrains = new HashSet<>();
        String previousStrongAction = null;
        String previousStrongTrain = null;

        for (Candidate candidate : candidates) {
            if (decisions.size() >= Math.max(MAX_COMMANDS_PER_ROUND, context.profiles().size())) {
                break;
            }
            TrainRunProfile profile = candidate.profile();
            TrainState train = trainById.get(profile.trainId());
            MovementAuthority authority = authorityByTrain.get(profile.trainId());
            RouteReservation reservation = reservationByTrain.get(profile.trainId());
            DecisionDraft draft = draftDecision(
                context,
                planId,
                candidate,
                train,
                authority,
                reservation,
                trainsWithActiveTimeCommand.contains(profile.trainId()),
                previousStrongAction,
                previousStrongTrain
            );
            decisions.add(draft.decision());
            if (draft.command() != null && commands.size() < MAX_COMMANDS_PER_ROUND) {
                commands.add(draft.command());
                commandedTrains.add(profile.trainId());
                if (isStrongAction(draft.decision().action())) {
                    previousStrongAction = draft.decision().action();
                    previousStrongTrain = profile.trainId();
                }
            }
        }

        List<LineRegulationDecision> visibleDecisions = decisions.stream()
            .filter(decision -> commandedTrains.contains(decision.trainId())
                || "OBSERVE".equals(decision.action())
                || decisions.indexOf(decision) < 4)
            .limit(6)
            .toList();
        LineRegulationPlan plan = new LineRegulationPlan(
            planId,
            context.simulatedAt(),
            "RESTORE_EVEN_HEADWAY",
            commands.isEmpty() && visibleDecisions.isEmpty() ? "NO_ACTION_NEEDED" : commands.isEmpty() ? "OBSERVING" : "COMMANDS_PROPOSED",
            planTargetHeadway,
            currentMaxAbsError(candidates),
            predictedMaxAbsError(candidates, visibleDecisions),
            commands.size(),
            visibleDecisions
        );
        return new LineHeadwayOptimizationResult(plan, commands);
    }

    private Candidate candidateFor(TrainRunProfile profile, DisturbanceEvent event, int planTargetHeadway) {
        String direction = event == null ? directionFromProfile(profile) : event.headwayDirection();
        Double actual = event == null ? profile.headwayActualSec() : event.actualHeadwaySec();
        int targetHeadway = event != null && event.targetHeadwaySec() != null && event.targetHeadwaySec() > 0
            ? (int) Math.round(event.targetHeadwaySec())
            : planTargetHeadway;
        double error = actual == null ? profile.headwayDeviationSec() : actual - targetHeadway;
        double tolerance = event != null && event.toleranceSec() != null
            ? event.toleranceSec()
            : Math.max(5, targetHeadway * 0.1);
        double priority = Math.abs(error) <= tolerance && !"SCHEDULE_LATE".equals(direction)
            ? 0
            : Math.max(Math.abs(error), event == null ? 0 : event.deviationValue());
        return new Candidate(profile, event, direction, targetHeadway, actual, error, tolerance, priority);
    }

    private DecisionDraft draftDecision(
        LineRegulationContext context,
        String planId,
        Candidate candidate,
        TrainState train,
        MovementAuthority authority,
        RouteReservation reservation,
        boolean hasActiveCommand,
        String previousStrongAction,
        String previousStrongTrain
    ) {
        TrainRunProfile profile = candidate.profile();
        String decisionId = "LRD-" + UUID.randomUUID().toString().substring(0, 8);
        String signalConstraint = signalConstraint(candidate, train, authority, reservation);
        String action = actionFor(candidate, profile, train, signalConstraint);
        String commandType = commandTypeFor(action, profile);
        String status = "COMMAND_PROPOSED";
        String reason = reasonFor(candidate, action, signalConstraint);

        if (hasActiveCommand) {
            action = TrainRegulationAction.OBSERVE;
            commandType = "OBSERVE";
            status = "OBSERVE_ACTIVE_COMMAND";
            reason = "本车已有活动时间调度命令，本轮观察避免重复下发。";
        } else if (isConflictingStrongAction(action, previousStrongAction, previousStrongTrain, profile.frontTrainId())) {
            action = TrainRegulationAction.OBSERVE;
            commandType = "OBSERVE";
            status = "OBSERVE_CONFLICT_SUPPRESSED";
            reason = "相邻车已有相反强动作，本轮降级观察以避免局部震荡。";
        } else if ("OBSERVE".equals(action)) {
            commandType = "OBSERVE";
            status = "NONE".equals(signalConstraint) ? "OBSERVE" : "OBSERVE_SIGNAL_CONSTRAINED";
        }

        Map<String, Object> payload = payloadFor(
            context,
            planId,
            decisionId,
            candidate,
            action,
            commandType,
            predictedError(candidate, action)
        );
        DispatchCommand command = "OBSERVE".equals(commandType)
            ? null
            : new DispatchCommand(
                "CMD-" + UUID.randomUUID().toString().substring(0, 8),
                profile.trainId(),
                commandType,
                payload,
                "LINE_HEADWAY_OPTIMIZATION",
                CommandStatus.PENDING,
                context.simulatedAt(),
                null
            );
        LineRegulationDecision decision = new LineRegulationDecision(
            profile.trainId(),
            profile.trainId(),
            profile.frontTrainId(),
            action,
            commandType,
            status,
            reason,
            candidate.actualHeadwaySec(),
            candidate.targetHeadwaySec(),
            candidate.headwayErrorSec(),
            predictedError(candidate, action),
            candidate.priorityScore(),
            signalConstraint,
            command == null ? null : command.id(),
            payload
        );
        return new DecisionDraft(decision, command);
    }

    private String actionFor(Candidate candidate, TrainRunProfile profile, TrainState train, String signalConstraint) {
        if ("TOO_SHORT".equals(candidate.direction())) {
            return TrainRegulationAction.SLOW_DOWN;
        }
        if ("TOO_LONG".equals(candidate.direction())) {
            return "NONE".equals(signalConstraint) || isDwellingLike(profile, train)
                ? TrainRegulationAction.CATCH_UP
                : TrainRegulationAction.OBSERVE;
        }
        if ("SCHEDULE_LATE".equals(candidate.direction())) {
            if ("TOO_SHORT".equals(profile.headwayState())) {
                return TrainRegulationAction.OBSERVE;
            }
            return "NONE".equals(signalConstraint) || isDwellingLike(profile, train)
                ? TrainRegulationAction.CATCH_UP
                : TrainRegulationAction.OBSERVE;
        }
        return TrainRegulationAction.OBSERVE;
    }

    private String commandTypeFor(String action, TrainRunProfile profile) {
        if (TrainRegulationAction.SLOW_DOWN.equals(action)) {
            return isDwellingLike(profile, null) ? "EXTEND_DWELL" : "SPEED_BIAS";
        }
        if (TrainRegulationAction.CATCH_UP.equals(action)) {
            return isDwellingLike(profile, null) ? "SHORTEN_DWELL" : "SPEED_BIAS";
        }
        return "OBSERVE";
    }

    private Map<String, Object> payloadFor(
        LineRegulationContext context,
        String planId,
        String decisionId,
        Candidate candidate,
        String action,
        String commandType,
        Double predictedError
    ) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("simulationRunId", context.simulationRunId());
        payload.put("regulatedTrainId", candidate.profile().trainId());
        putIfNotNull(payload, "frontTrainId", candidate.profile().frontTrainId());
        payload.put("regulationAction", action);
        payload.put("regulationSource", "LINE_HEADWAY_OPTIMIZATION");
        payload.put("lineRegulationPlanId", planId);
        payload.put("lineRegulationDecisionId", decisionId);
        payload.put("targetHeadwaySec", candidate.targetHeadwaySec());
        putIfNotNull(payload, "actualHeadwaySec", candidate.actualHeadwaySec());
        payload.put("baselineHeadwayErrorSec", candidate.headwayErrorSec());
        putIfNotNull(payload, "predictedHeadwayErrorSec", predictedError);
        payload.put("headwayDirection", candidate.direction());
        payload.put("headwayToleranceSec", candidate.toleranceSec());
        payload.put("headwayViolationSec", Math.max(0, Math.abs(candidate.headwayErrorSec()) - candidate.toleranceSec()));
        payload.put("effectConfirmationStandard", "LINE_HEADWAY_ERROR_IMPROVED_OR_WITHIN_TOLERANCE");
        if (candidate.event() != null) {
            payload.put("disturbanceId", candidate.event().id());
        }
        if ("SPEED_BIAS".equals(commandType)) {
            payload.put("speedBiasRatio", speedBiasRatio(candidate));
        } else if ("EXTEND_DWELL".equals(commandType) || "SHORTEN_DWELL".equals(commandType)) {
            payload.put("deltaDwellSec", dwellDelta(candidate, commandType));
            payload.put("executeOnNextDwell", true);
            if (candidate.event() != null && candidate.event().stationId() != null && !candidate.event().stationId().isBlank()) {
                payload.put("targetStationId", candidate.event().stationId());
            }
        }
        return payload;
    }

    private void putIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private String signalConstraint(
        Candidate candidate,
        TrainState train,
        MovementAuthority authority,
        RouteReservation reservation
    ) {
        if (!"TOO_LONG".equals(candidate.direction()) && !"SCHEDULE_LATE".equals(candidate.direction())) {
            return "NONE";
        }
        if (reservation != null && RouteReservationState.REJECTED.equals(reservation.state())) {
            return "ROUTE_BLOCKED";
        }
        if (train != null && authority != null) {
            double maDistance = authority.authorityEndMeters() - train.positionMeters();
            if (maDistance < 80) {
                return "MA_LIMITED";
            }
            if (authority.speedLimitMetersPerSecond() <= Math.max(1.0, train.speedMetersPerSecond() + 0.5)) {
                return "SPEED_LIMITED";
            }
        }
        return "NONE";
    }

    private String reasonFor(Candidate candidate, String action, String signalConstraint) {
        if (!"NONE".equals(signalConstraint) && TrainRegulationAction.OBSERVE.equals(action)) {
            return "本车追赶受信号约束限制，等待 MA/进路条件改善。";
        }
        String direction = switch (candidate.direction()) {
            case "TOO_SHORT" -> "间隔过小";
            case "TOO_LONG" -> "间隔过大";
            case "SCHEDULE_LATE" -> "本车晚点";
            default -> "间隔异常";
        };
        return direction + "，线路级优化选择" + actionLabel(action) + "。";
    }

    private String actionLabel(String action) {
        return switch (action) {
            case TrainRegulationAction.SLOW_DOWN -> "本车放慢";
            case TrainRegulationAction.CATCH_UP -> "本车追赶";
            default -> "观察";
        };
    }

    private String directionFromProfile(TrainRunProfile profile) {
        if ("TOO_SHORT".equals(profile.headwayState())) {
            return "TOO_SHORT";
        }
        if ("TOO_LONG".equals(profile.headwayState())) {
            return "TOO_LONG";
        }
        if (profile.departureDelaySec() > 0 && profile.frontTrainId() == null) {
            return "SCHEDULE_LATE";
        }
        return "ON_TARGET";
    }

    private boolean isDwellingLike(TrainRunProfile profile, TrainState train) {
        if (profile != null && ("DWELLING".equals(profile.status())
            || "STOPPED".equals(profile.status())
            || "READY".equals(profile.status())
            || profile.speedMps() <= 0.5)) {
            return true;
        }
        return train != null && ("DWELLING".equals(train.status()) || train.speedMetersPerSecond() <= 0.5);
    }

    private int dwellDelta(Candidate candidate, String commandType) {
        int value = Math.max(3, Math.min(10, (int) Math.ceil(
            Math.max(candidate.priorityScore(), Math.abs(candidate.headwayErrorSec())) / 30.0
        )));
        return "EXTEND_DWELL".equals(commandType) ? value : -value;
    }

    private double speedBiasRatio(Candidate candidate) {
        double violation = Math.max(candidate.priorityScore(), Math.abs(candidate.headwayErrorSec()));
        if ("TOO_SHORT".equals(candidate.direction())) {
            if (violation >= 120) {
                return 0.65;
            }
            if (violation >= 60) {
                return 0.75;
            }
            return 0.85;
        }
        if (violation >= 120) {
            return 1.18;
        }
        if (violation >= 60) {
            return 1.12;
        }
        return 1.08;
    }

    private Double predictedError(Candidate candidate, String action) {
        if (TrainRegulationAction.OBSERVE.equals(action)) {
            return candidate.headwayErrorSec();
        }
        double correction = Math.min(Math.abs(candidate.headwayErrorSec()), Math.max(10, candidate.priorityScore() * 0.35));
        if (candidate.headwayErrorSec() < 0) {
            return candidate.headwayErrorSec() + correction;
        }
        return candidate.headwayErrorSec() - correction;
    }

    private boolean isStrongAction(String action) {
        return TrainRegulationAction.SLOW_DOWN.equals(action) || TrainRegulationAction.CATCH_UP.equals(action);
    }

    private boolean isConflictingStrongAction(
        String action,
        String previousStrongAction,
        String previousStrongTrain,
        String frontTrainId
    ) {
        return isStrongAction(action)
            && previousStrongAction != null
            && previousStrongTrain != null
            && previousStrongTrain.equals(frontTrainId)
            && !previousStrongAction.equals(action);
    }

    private Double currentMaxAbsError(List<Candidate> candidates) {
        return candidates.stream()
            .mapToDouble(candidate -> Math.abs(candidate.headwayErrorSec()))
            .max()
            .stream()
            .boxed()
            .findFirst()
            .orElse(null);
    }

    private Double predictedMaxAbsError(List<Candidate> candidates, List<LineRegulationDecision> decisions) {
        if (candidates.isEmpty()) {
            return null;
        }
        Map<String, Double> predictedByTrain = new HashMap<>();
        for (LineRegulationDecision decision : decisions) {
            predictedByTrain.put(decision.trainId(), decision.predictedHeadwayErrorSec());
        }
        return candidates.stream()
            .mapToDouble(candidate -> Math.abs(predictedByTrain.getOrDefault(
                candidate.profile().trainId(), candidate.headwayErrorSec()
            )))
            .max()
            .stream()
            .boxed()
            .findFirst()
            .orElse(null);
    }

    private Map<String, TrainState> mapTrains(List<TrainState> trains) {
        Map<String, TrainState> result = new HashMap<>();
        for (TrainState train : trains) {
            result.put(train.id(), train);
        }
        return result;
    }

    private Map<String, MovementAuthority> mapAuthorities(List<MovementAuthority> authorities) {
        Map<String, MovementAuthority> result = new HashMap<>();
        for (MovementAuthority authority : authorities) {
            result.put(authority.trainId(), authority);
        }
        return result;
    }

    private Map<String, DisturbanceEvent> mapRegulationEvents(List<DisturbanceEvent> events) {
        Map<String, DisturbanceEvent> result = new HashMap<>();
        for (DisturbanceEvent event : events) {
            if ("OPEN".equals(event.status()) && event.headwayDirection() != null) {
                result.put(event.trainId(), event);
            }
        }
        return result;
    }

    private Set<String> activeTimeCommandTrains(List<DispatchCommand> commands) {
        Set<String> result = new HashSet<>();
        for (DispatchCommand command : commands) {
            if (CommandStatus.PENDING.equals(command.status())
                || CommandStatus.SENT.equals(command.status())
                || CommandStatus.APPLIED.equals(command.status())) {
                String type = command.commandType();
                if ("SPEED_BIAS".equals(type)
                    || "EXTEND_DWELL".equals(type)
                    || "SHORTEN_DWELL".equals(type)
                    || "HEADWAY_ADJUST".equals(type)
                    || "HOLD".equals(type)
                    || "HOLD_TRAIN".equals(type)) {
                    result.add(command.trainId());
                }
            }
        }
        return result;
    }

    private Map<String, RouteReservation> latestReservationByTrain(List<RouteReservation> reservations) {
        Map<String, RouteReservation> result = new HashMap<>();
        for (RouteReservation reservation : reservations) {
            result.put(reservation.trainId(), reservation);
        }
        return result;
    }

    private record Candidate(
        TrainRunProfile profile,
        DisturbanceEvent event,
        String direction,
        int targetHeadwaySec,
        Double actualHeadwaySec,
        double headwayErrorSec,
        double toleranceSec,
        double priorityScore
    ) {
    }

    private record DecisionDraft(LineRegulationDecision decision, DispatchCommand command) {
    }
}
