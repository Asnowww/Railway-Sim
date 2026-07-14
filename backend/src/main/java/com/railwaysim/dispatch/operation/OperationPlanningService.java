package com.railwaysim.dispatch.operation;

import com.railwaysim.dispatch.route.DispatchRouteCandidate;
import com.railwaysim.dispatch.route.RouteCatalog;
import com.railwaysim.train.TrainState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OperationPlanningService {

    private static final String GGZ_TERMINAL_ID = "S101";
    private static final String LIB_TERMINAL_ID = "S113";
    private static final double TERMINAL_POSITION_TOLERANCE_METERS = 150.0;
    private static final List<String> M9_LOOP_ROUTE_IDS = List.of("R_UP", "R_TB_LIB", "R_DOWN", "R_TB_GGZ");

    private final RouteCatalog routeCatalog;
    private final Map<String, OperationPlan> plans = new LinkedHashMap<>();
    private final Map<String, TrainCirculationPlan> circulationPlans = new LinkedHashMap<>();

    public OperationPlanningService(RouteCatalog routeCatalog) {
        this.routeCatalog = routeCatalog;
    }

    public synchronized void clear() {
        plans.clear();
        circulationPlans.clear();
    }

    public List<OperationRouteTemplate> templates() {
        return defaultTemplates().stream()
            .map(this::enrichFromCatalog)
            .toList();
    }

    public List<OperationRouteCandidate> preview(OperationPlanRequest request) {
        List<String> required = normalizeIds(request == null ? List.of() : request.pointIds());
        if (required.size() < 2) {
            return List.of();
        }
        String routeFilter = normalizeId(request.routeId());
        return templates().stream()
            .filter(template -> routeFilter == null || routeFilter.equals(template.routeId()))
            .flatMap(template -> {
                List<OperationRouteCandidate> matches = new ArrayList<>();
                for (String direction : routeDirections(template)) {
                    OperationRouteCandidate candidate = match(template, required, direction);
                    if (candidate != null) {
                        matches.add(candidate);
                    }
                }
                return matches.stream();
            })
            .sorted(Comparator
                .comparing((OperationRouteCandidate candidate) -> candidate.segmentIds().size())
                .thenComparing(OperationRouteCandidate::routeId)
                .thenComparing(OperationRouteCandidate::direction))
            .toList();
    }

    public synchronized OperationPlan create(
        String simulationRunId,
        OperationPlanRequest request,
        List<TrainState> trains,
        Instant simulatedAt
    ) {
        if (request == null) {
            throw new IllegalArgumentException("operation plan request is required");
        }
        OperationRouteCandidate candidate = selectCandidate(request);
        String trainId = normalizeText(request.trainId());
        if (trainId == null) {
            trainId = autoAssignTrain(candidate, trains, simulationRunId);
        }
        if (trainId == null) {
            throw new IllegalArgumentException("no available train for operation plan");
        }
        Instant now = simulatedAt == null ? Instant.now() : simulatedAt;
        Instant departureAt = request.plannedDepartureAt();
        if (departureAt == null) {
            int lead = clamp(request.leadSeconds() == null ? 60 : request.leadSeconds(), 0, 3600);
            int headway = clamp(request.headwaySeconds() == null ? 300 : request.headwaySeconds(), 30, 3600);
            long plannedCount = list(simulationRunId).stream()
                .filter(plan -> OperationPlanStatus.PLANNED.equals(plan.status()))
                .count();
            departureAt = now.plusSeconds(lead + plannedCount * headway);
        }

        List<String> requested = normalizeIds(request.pointIds());
        String planId = "OP-" + UUID.randomUUID().toString().substring(0, 8);
        OperationPlan plan = new OperationPlan(
            planId,
            simulationRunId,
            candidate.routeId(),
            candidate.routeName(),
            candidate.direction(),
            trainId,
            requested.get(0),
            requested.get(requested.size() - 1),
            requested.size() <= 2 ? List.of() : requested.subList(1, requested.size() - 1),
            candidate.pointIds(),
            candidate.stationIds(),
            candidate.segmentIds(),
            departureAt,
            OperationPlanStatus.PLANNED,
            request.priority() == null ? 0 : Math.max(0, request.priority()),
            1,
            now,
            now,
            null,
            null,
            null,
            null,
            null,
            null
        );
        plans.put(planId, plan);
        return plan;
    }

    public synchronized List<TrainCirculationPlan> autoAssignCirculations(
        String simulationRunId,
        List<TrainState> trains,
        Instant simulatedAt,
        CirculationPlanRequest request
    ) {
        return assignCirculations(simulationRunId, trains, simulatedAt, request, false);
    }

    public synchronized List<TrainCirculationPlan> autoAssignNewTrainCirculations(
        String simulationRunId,
        List<TrainState> trains,
        Instant simulatedAt,
        CirculationPlanRequest request
    ) {
        return assignCirculations(simulationRunId, trains, simulatedAt, request, true);
    }

    private List<TrainCirculationPlan> assignCirculations(
        String simulationRunId,
        List<TrainState> trains,
        Instant simulatedAt,
        CirculationPlanRequest request,
        boolean onlyNeverAssigned
    ) {
        Instant now = simulatedAt == null ? Instant.now() : simulatedAt;
        int cycleTarget = clamp(request == null || request.cycleTarget() == null ? 2 : request.cycleTarget(), 1, 20);
        int headwaySeconds = clamp(request == null || request.headwaySeconds() == null ? 300 : request.headwaySeconds(), 30, 3600);
        int leadSeconds = clamp(request == null || request.leadSeconds() == null ? 30 : request.leadSeconds(), 0, 3600);
        List<TrainState> candidates = trains == null ? List.of() : trains.stream()
            .filter(train -> train.id() != null && !train.id().isBlank())
            .filter(train -> train.faultLevel() <= 1)
            .filter(train -> onlyNeverAssigned
                ? circulationForTrain(simulationRunId, train.id()) == null
                : activeCirculationForTrain(simulationRunId, train.id()) == null)
            .toList();
        List<TrainState> upStart = candidates.stream()
            .filter(train -> GGZ_TERMINAL_ID.equals(resolveInitialTerminal(train)))
            .sorted(Comparator.comparing(TrainState::id))
            .toList();
        List<TrainState> downStart = candidates.stream()
            .filter(train -> LIB_TERMINAL_ID.equals(resolveInitialTerminal(train)))
            .sorted(Comparator.comparing(TrainState::id))
            .toList();
        if (upStart.isEmpty() && downStart.isEmpty()) {
            return List.of();
        }
        if (!hasM9LoopTemplates()) {
            return List.of();
        }

        List<TrainCirculationPlan> created = new ArrayList<>();
        for (int i = 0; i < upStart.size(); i++) {
            created.add(createCirculationPlan(
                simulationRunId, upStart.get(i), GGZ_TERMINAL_ID, 0, cycleTarget, headwaySeconds,
                now.plusSeconds((long) leadSeconds + (long) i * headwaySeconds), now));
        }
        for (int i = 0; i < downStart.size(); i++) {
            created.add(createCirculationPlan(
                simulationRunId, downStart.get(i), LIB_TERMINAL_ID, 2, cycleTarget, headwaySeconds,
                now.plusSeconds((long) leadSeconds + (long) i * headwaySeconds), now));
        }
        return created;
    }

    public synchronized OperationPlan createPlanForCurrentCirculationLeg(
        TrainCirculationPlan circulation,
        Instant simulatedAt
    ) {
        if (circulation == null || circulation.currentLeg() == null) {
            return null;
        }
        CirculationLeg leg = circulation.currentLeg();
        if (!CirculationLegStatus.PLANNED.equals(leg.status()) || leg.operationPlanId() != null) {
            return null;
        }
        Instant now = simulatedAt == null ? Instant.now() : simulatedAt;
        String planId = "OP-" + leg.legId();
        OperationPlan plan = new OperationPlan(
            planId,
            circulation.simulationRunId(),
            leg.routeId(),
            leg.routeName(),
            leg.direction(),
            circulation.trainId(),
            leg.fromPointId(),
            leg.toPointId(),
            leg.pointIds().size() <= 2 ? List.of() : leg.pointIds().subList(1, leg.pointIds().size() - 1),
            leg.pointIds(),
            leg.stationIds(),
            leg.segmentIds(),
            leg.plannedDepartureAt(),
            OperationPlanStatus.PLANNED,
            10,
            1,
            now,
            now,
            null,
            null,
            circulation.circulationId(),
            leg.legId(),
            leg.cycleIndex(),
            leg.legIndex()
        );
        plans.put(planId, plan);
        updateLeg(circulation.circulationId(), leg.legIndex(), leg.withPlan(planId, leg.plannedDepartureAt()),
            CirculationPlanStatus.ASSIGNED, now);
        return plan;
    }

    public synchronized OperationPlan cancel(String planId, Instant simulatedAt) {
        OperationPlan plan = plans.get(planId);
        if (plan == null) {
            throw new IllegalArgumentException("operation plan not found: " + planId);
        }
        OperationPlan cancelled = plan.withStatus(OperationPlanStatus.CANCELLED, null,
            simulatedAt == null ? Instant.now() : simulatedAt);
        plans.put(planId, cancelled);
        return cancelled;
    }

    public synchronized List<OperationPlan> list(String simulationRunId) {
        return plans.values().stream()
            .filter(plan -> simulationRunId == null || simulationRunId.equals(plan.simulationRunId()))
            .sorted(Comparator
                .comparing(OperationPlan::plannedDepartureAt)
                .thenComparing(OperationPlan::planId))
            .toList();
    }

    public synchronized List<OperationPlan> duePlans(String simulationRunId, Instant simulatedAt) {
        return list(simulationRunId).stream()
            .filter(plan -> OperationPlanStatus.PLANNED.equals(plan.status()))
            .filter(plan -> !plan.plannedDepartureAt().isAfter(simulatedAt))
            .toList();
    }

    public synchronized void markRouteRequested(String planId, String routeCommandId, Instant simulatedAt) {
        OperationPlan plan = plans.get(planId);
        if (plan == null || !OperationPlanStatus.PLANNED.equals(plan.status())) {
            return;
        }
        OperationPlan requested = plan.withRouteRequested(routeCommandId, simulatedAt);
        plans.put(planId, requested);
        updateCirculationLegFromPlan(requested, CirculationLegStatus.ROUTE_REQUESTED, routeCommandId, null, simulatedAt);
    }

    public synchronized void updateFromRouteFeedback(String planId, boolean accepted, String rejectReason, Instant simulatedAt) {
        OperationPlan plan = plans.get(planId);
        if (plan == null || OperationPlanStatus.CANCELLED.equals(plan.status())) {
            return;
        }
        OperationPlan updated = plan.withStatus(
            accepted ? OperationPlanStatus.ROUTE_ACCEPTED : OperationPlanStatus.ROUTE_REJECTED,
            accepted ? null : rejectReason,
            simulatedAt == null ? Instant.now() : simulatedAt
        );
        plans.put(planId, updated);
        updateCirculationLegFromPlan(
            updated,
            accepted ? CirculationLegStatus.ROUTE_ACCEPTED : CirculationLegStatus.ROUTE_REJECTED,
            updated.routeCommandId(),
            accepted ? null : rejectReason,
            simulatedAt
        );
        if (accepted && updated.circulationPlanId() != null) {
            completeCirculationLeg(updated.circulationPlanId(), updated.circulationLegId(), simulatedAt);
        }
    }

    public synchronized List<TrainCirculationPlan> circulationPlans(String simulationRunId) {
        return circulationPlans.values().stream()
            .filter(plan -> simulationRunId == null || simulationRunId.equals(plan.simulationRunId()))
            .sorted(Comparator
                .comparing(TrainCirculationPlan::plannedStartAt)
                .thenComparing(TrainCirculationPlan::trainId))
            .toList();
    }

    public synchronized TrainCirculationPlan cancelCirculation(String circulationId, Instant simulatedAt) {
        TrainCirculationPlan plan = circulationPlans.get(circulationId);
        if (plan == null) {
            throw new IllegalArgumentException("circulation plan not found: " + circulationId);
        }
        TrainCirculationPlan cancelled = plan.cancelled(simulatedAt == null ? Instant.now() : simulatedAt);
        circulationPlans.put(circulationId, cancelled);
        return cancelled;
    }

    private OperationRouteCandidate selectCandidate(OperationPlanRequest request) {
        List<OperationRouteCandidate> candidates = preview(request);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("no fixed route matches selected points: " + request.pointIds());
        }
        String key = normalizeText(request.candidateKey());
        if (key != null) {
            return candidates.stream()
                .filter(candidate -> key.equals(candidate.key()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("candidate not found: " + request.candidateKey()));
        }
        return candidates.get(0);
    }

    private String autoAssignTrain(OperationRouteCandidate candidate, List<TrainState> trains, String simulationRunId) {
        if (trains == null || trains.isEmpty()) {
            return null;
        }
        List<String> assigned = list(simulationRunId).stream()
            .filter(plan -> OperationPlanStatus.PLANNED.equals(plan.status())
                || OperationPlanStatus.ROUTE_REQUESTED.equals(plan.status()))
            .map(OperationPlan::trainId)
            .toList();
        String origin = candidate.stationIds().isEmpty() ? null : candidate.stationIds().get(0);
        return trains.stream()
            .filter(train -> !assigned.contains(train.id()))
            .filter(train -> train.faultLevel() <= 1)
            .min(Comparator.comparingDouble(train -> trainScore(train, origin)))
            .map(TrainState::id)
            .orElse(null);
    }

    private TrainCirculationPlan createCirculationPlan(
        String simulationRunId,
        TrainState train,
        String startTerminalId,
        int startIndex,
        int cycleTarget,
        int headwaySeconds,
        Instant plannedStartAt,
        Instant now
    ) {
        String circulationId = "CIRC-" + train.id() + "-" + UUID.randomUUID().toString().substring(0, 6);
        List<CirculationLeg> legs = new ArrayList<>();
        Instant cursor = plannedStartAt;
        List<OperationRouteTemplate> loop = m9LoopTemplates();
        for (int cycle = 0; cycle < cycleTarget; cycle++) {
            for (int step = 0; step < loop.size(); step++) {
                int templateIndex = (startIndex + step) % loop.size();
                OperationRouteTemplate template = loop.get(templateIndex);
                int absoluteIndex = cycle * loop.size() + step;
                String legId = circulationId + "-L" + String.format(Locale.ROOT, "%02d", absoluteIndex + 1);
                legs.add(new CirculationLeg(
                    legId,
                    template.routeId(),
                    template.name(),
                    directionForLoopRoute(template.routeId()),
                    template.typeCode() == null || "MAIN".equals(template.typeCode()) ? "MAIN_RUN" : "TURNBACK",
                    template.pointIds().isEmpty() ? "" : template.pointIds().get(0),
                    template.pointIds().isEmpty() ? "" : template.pointIds().get(template.pointIds().size() - 1),
                    template.pointIds(),
                    template.stationIds(),
                    template.segmentIds(),
                    cycle,
                    absoluteIndex,
                    cursor,
                    CirculationLegStatus.PLANNED,
                    null,
                    null,
                    null
                ));
                cursor = cursor.plusSeconds(secondsForLeg(template));
            }
        }
        TrainCirculationPlan plan = new TrainCirculationPlan(
            circulationId,
            simulationRunId,
            "M9_LOOP",
            train.id(),
            startTerminalId,
            cycleTarget,
            0,
            0,
            CirculationPlanStatus.ASSIGNED,
            headwaySeconds,
            plannedStartAt,
            now,
            now,
            legs
        );
        circulationPlans.put(circulationId, plan);
        return plan;
    }

    private TrainCirculationPlan activeCirculationForTrain(String simulationRunId, String trainId) {
        return circulationPlans(simulationRunId).stream()
            .filter(plan -> trainId.equals(plan.trainId()))
            .filter(plan -> !CirculationPlanStatus.CANCELLED.equals(plan.status()))
            .filter(plan -> !CirculationPlanStatus.RESTING.equals(plan.status()))
            .findFirst()
            .orElse(null);
    }

    private TrainCirculationPlan circulationForTrain(String simulationRunId, String trainId) {
        return circulationPlans(simulationRunId).stream()
            .filter(plan -> trainId.equals(plan.trainId()))
            .findFirst()
            .orElse(null);
    }

    private String resolveInitialTerminal(TrainState train) {
        if (train == null) {
            return null;
        }
        String stationId = normalizeId(train.currentStationId());
        if (GGZ_TERMINAL_ID.equals(stationId) || LIB_TERMINAL_ID.equals(stationId)) {
            return stationId;
        }
        if (!Double.isFinite(train.positionMeters())) {
            return null;
        }
        TerminalPositions terminals = terminalPositions();
        double position = train.positionMeters();
        if (Double.isFinite(terminals.ggzMeters())
            && Math.abs(position - terminals.ggzMeters()) <= TERMINAL_POSITION_TOLERANCE_METERS) {
            return GGZ_TERMINAL_ID;
        }
        if (Double.isFinite(terminals.libMeters())
            && Math.abs(position - terminals.libMeters()) <= TERMINAL_POSITION_TOLERANCE_METERS) {
            return LIB_TERMINAL_ID;
        }
        return null;
    }

    private TerminalPositions terminalPositions() {
        DispatchRouteCandidate up = routeCatalog.route("R_UP").orElse(null);
        DispatchRouteCandidate down = routeCatalog.route("R_DOWN").orElse(null);
        double ggzMeters = up != null ? up.entryMeters() : down != null ? down.exitMeters() : 0.0;
        double libMeters = up != null ? up.exitMeters() : down != null ? down.entryMeters() : Double.NaN;
        return new TerminalPositions(ggzMeters, libMeters);
    }

    private record TerminalPositions(double ggzMeters, double libMeters) {
    }

    private void updateCirculationLegFromPlan(
        OperationPlan plan,
        String legStatus,
        String routeCommandId,
        String rejectReason,
        Instant updatedAt
    ) {
        if (plan.circulationPlanId() == null || plan.legIndex() == null) {
            return;
        }
        TrainCirculationPlan circulation = circulationPlans.get(plan.circulationPlanId());
        if (circulation == null || plan.legIndex() < 0 || plan.legIndex() >= circulation.legs().size()) {
            return;
        }
        CirculationLeg leg = circulation.legs().get(plan.legIndex());
        String circulationStatus = CirculationLegStatus.ROUTE_REJECTED.equals(legStatus)
            ? CirculationPlanStatus.BLOCKED
            : CirculationPlanStatus.WAITING_ROUTE;
        updateLeg(
            circulation.circulationId(),
            plan.legIndex(),
            leg.withStatus(legStatus, routeCommandId, rejectReason),
            circulationStatus,
            updatedAt == null ? Instant.now() : updatedAt
        );
    }

    private void completeCirculationLeg(String circulationId, String legId, Instant updatedAt) {
        TrainCirculationPlan circulation = circulationPlans.get(circulationId);
        if (circulation == null || CirculationPlanStatus.CANCELLED.equals(circulation.status())) {
            return;
        }
        int index = circulation.currentLegPointer();
        if (index < 0 || index >= circulation.legs().size()) {
            return;
        }
        CirculationLeg leg = circulation.legs().get(index);
        if (legId != null && !legId.equals(leg.legId())) {
            return;
        }
        Instant now = updatedAt == null ? Instant.now() : updatedAt;
        TrainCirculationPlan withCompletedLeg = updateLeg(
            circulation.circulationId(),
            index,
            leg.withStatus(CirculationLegStatus.COMPLETED, leg.routeCommandId(), null),
            CirculationPlanStatus.IN_SERVICE,
            now
        );
        TrainCirculationPlan advanced = withCompletedLeg.advanceAfterCompletedLeg(now);
        circulationPlans.put(circulationId, advanced);
    }

    private TrainCirculationPlan updateLeg(
        String circulationId,
        int index,
        CirculationLeg leg,
        String status,
        Instant updatedAt
    ) {
        TrainCirculationPlan plan = circulationPlans.get(circulationId);
        if (plan == null || index < 0 || index >= plan.legs().size()) {
            return plan;
        }
        TrainCirculationPlan updated = plan.withLeg(index, leg, status, updatedAt == null ? Instant.now() : updatedAt);
        circulationPlans.put(circulationId, updated);
        return updated;
    }

    private List<OperationRouteTemplate> m9LoopTemplates() {
        Map<String, OperationRouteTemplate> byId = new LinkedHashMap<>();
        for (OperationRouteTemplate template : templates()) {
            byId.put(template.routeId(), template);
        }
        List<OperationRouteTemplate> loop = new ArrayList<>();
        for (String routeId : M9_LOOP_ROUTE_IDS) {
            OperationRouteTemplate template = byId.get(routeId);
            if (template == null) {
                throw new IllegalStateException("missing signal route template: " + routeId);
            }
            loop.add(template);
        }
        return loop;
    }

    private boolean hasM9LoopTemplates() {
        List<String> available = templates().stream().map(OperationRouteTemplate::routeId).toList();
        return available.containsAll(M9_LOOP_ROUTE_IDS);
    }

    private static String directionForLoopRoute(String routeId) {
        if ("R_DOWN".equals(routeId) || "R_TB_GGZ".equals(routeId)) {
            return "DOWN";
        }
        return "UP";
    }

    private static long secondsForLeg(OperationRouteTemplate template) {
        if (template == null || !"MAIN".equals(template.typeCode())) {
            return 90;
        }
        return 360;
    }

    private double trainScore(TrainState train, String originStationId) {
        double score = Math.max(0, train.faultLevel()) * 100_000.0;
        if (originStationId != null && originStationId.equals(train.currentStationId())) {
            score -= 100_000.0;
        }
        if (train.speedMetersPerSecond() > 0.5) {
            score += 20_000.0;
        }
        if (!"DWELLING".equals(train.status()) && !"READY".equals(train.status()) && !"STOPPED".equals(train.status())) {
            score += 8_000.0;
        }
        return score + Math.abs(train.positionMeters());
    }

    private OperationRouteCandidate match(OperationRouteTemplate template, List<String> required, String direction) {
        boolean directedTemplate = isDirectedSignalRoute(template.routeId());
        List<String> points = "UP".equals(direction) || directedTemplate
            ? template.pointIds()
            : reverse(template.pointIds());
        List<String> segments = "UP".equals(direction) || directedTemplate
            ? template.segmentIds()
            : reverse(template.segmentIds());
        List<Integer> indexes = new ArrayList<>();
        int cursor = -1;
        for (String requiredPoint : required) {
            int index = -1;
            for (int i = cursor + 1; i < points.size(); i++) {
                if (points.get(i).equals(requiredPoint)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return null;
            }
            indexes.add(index);
            cursor = index;
        }
        int start = indexes.get(0);
        int end = indexes.get(indexes.size() - 1);
        if (end <= start) {
            return null;
        }
        List<String> pathPoints = points.subList(start, end + 1);
        List<String> pathSegments = segments.size() == points.size() - 1
            ? segments.subList(start, end)
            : segments;
        return new OperationRouteCandidate(
            template.routeId() + ":" + direction,
            template.routeId(),
            template.name(),
            direction,
            pathPoints,
            pathPoints.stream().filter(OperationPlanningService::isStationId).toList(),
            pathSegments
        );
    }

    private OperationRouteTemplate enrichFromCatalog(OperationRouteTemplate template) {
        DispatchRouteCandidate route = routeCatalog.route(template.routeId()).orElse(null);
        if (route == null) {
            return template;
        }
        return new OperationRouteTemplate(
            template.routeId(),
            route.name() == null || route.name().isBlank() ? template.name() : route.name(),
            route.typeCode(),
            template.pointIds(),
            template.stationIds(),
            route.segmentIds().isEmpty() ? template.segmentIds() : route.segmentIds()
        );
    }

    private List<OperationRouteTemplate> defaultTemplates() {
        boolean hasM9Routes = routeCatalog.routes().stream()
            .anyMatch(route -> "R_UP".equals(route.routeId()) || "R_DOWN".equals(route.routeId()));
        if (hasM9Routes) {
            return List.of(
                new OperationRouteTemplate(
                    "R_UP", "上行正线（郭公庄→国家图书馆）", "MAIN",
                    List.of("S101", "S102", "S103", "S104", "S105", "S106", "S107",
                        "S108", "S109", "S110", "S111", "S112", "S113"),
                    List.of("S101", "S102", "S103", "S104", "S105", "S106", "S107",
                        "S108", "S109", "S110", "S111", "S112", "S113"),
                    List.of("U02", "U10", "U13", "U16", "U19", "U20", "U23",
                        "U27", "U34", "U38", "U42", "U46", "U48")
                ),
                new OperationRouteTemplate(
                    "R_DOWN", "下行正线（国家图书馆→郭公庄）", "MAIN",
                    List.of("S113", "S112", "S111", "S110", "S109", "S108", "S107",
                        "S106", "S105", "S104", "S103", "S102", "S101"),
                    List.of("S113", "S112", "S111", "S110", "S109", "S108", "S107",
                        "S106", "S105", "S104", "S103", "S102", "S101"),
                    List.of("D47", "D43", "D36", "D28", "D24", "D21",
                        "D17", "D14", "D11", "D07", "D04", "D03")
                ),
                new OperationRouteTemplate(
                    "R_TB_LIB", "国家图书馆折返（上行→下行）", "BRANCH",
                    List.of("S113", "D_START"),
                    List.of("S113"),
                    List.of("XLIB", "D47")
                ),
                new OperationRouteTemplate(
                    "R_TB_GGZ", "郭公庄折返（下行→上行）", "BRANCH",
                    List.of("S101", "U_START"),
                    List.of("S101"),
                    List.of("XGGZ", "U02")
                )
            );
        }
        return List.of(
            new OperationRouteTemplate(
                "R_MAIN", "主线", "MAIN",
                List.of("S01", "S02", "S03", "S04", "S05", "S06"),
                List.of("S01", "S02", "S03", "S04", "S05", "S06"),
                List.of("T01", "T02", "T03", "T04", "T05")
            ),
            new OperationRouteTemplate(
                "R_NORTH", "北线", "BRANCH",
                List.of("S02", "TECH_N", "RENMIN_N", "FIN_N", "EXPO_N", "S05"),
                List.of("S02", "S05"),
                List.of("T06", "T07", "T08", "T09", "T10")
            ),
            new OperationRouteTemplate(
                "R_DEPOT", "车辆段线", "DEPOT",
                List.of("DEPOT_B", "DEPOT_A", "S02"),
                List.of("S02"),
                List.of("T11", "T12")
            ),
            new OperationRouteTemplate(
                "R_LOOP", "折返联络线", "BRANCH",
                List.of("S03", "LOOP_W", "LOOP_E", "S04"),
                List.of("S03", "S04"),
                List.of("T13", "T14", "T15")
            ),
            new OperationRouteTemplate(
                "R_BRANCH", "会展支线", "BRANCH",
                List.of("S03", "LOOP_W", "LOOP_E", "BRANCH"),
                List.of("S03"),
                List.of("T13", "T14", "T16")
            )
        );
    }

    private static List<String> routeDirections(OperationRouteTemplate template) {
        return switch (template.routeId()) {
            case "R_UP" -> List.of("UP");
            case "R_DOWN" -> List.of("DOWN");
            case "R_TB_LIB", "R_TB_GGZ" -> List.of("UP");
            default -> List.of("UP", "DOWN");
        };
    }

    private static boolean isDirectedSignalRoute(String routeId) {
        return "R_UP".equals(routeId)
            || "R_DOWN".equals(routeId)
            || "R_TB_LIB".equals(routeId)
            || "R_TB_GGZ".equals(routeId);
    }

    private static List<String> normalizeIds(List<String> ids) {
        return ids.stream()
            .map(OperationPlanningService::normalizeId)
            .filter(id -> id != null)
            .toList();
    }

    private static String normalizeId(String id) {
        String normalized = normalizeText(id);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String normalizeText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    private static boolean isStationId(String pointId) {
        return pointId != null && pointId.matches("S\\d+");
    }

    private static <T> List<T> reverse(List<T> items) {
        List<T> copy = new ArrayList<>(items);
        java.util.Collections.reverse(copy);
        return copy;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.min(Math.max(value, minimum), maximum);
    }
}
