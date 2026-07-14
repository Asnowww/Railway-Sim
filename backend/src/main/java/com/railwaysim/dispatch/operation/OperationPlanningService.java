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

    private final RouteCatalog routeCatalog;
    private final Map<String, OperationPlan> plans = new LinkedHashMap<>();

    public OperationPlanningService(RouteCatalog routeCatalog) {
        this.routeCatalog = routeCatalog;
    }

    public synchronized void clear() {
        plans.clear();
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
            null
        );
        plans.put(planId, plan);
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
        plans.put(planId, plan.withRouteRequested(routeCommandId, simulatedAt));
    }

    public synchronized void updateFromRouteFeedback(String planId, boolean accepted, String rejectReason, Instant simulatedAt) {
        OperationPlan plan = plans.get(planId);
        if (plan == null || OperationPlanStatus.CANCELLED.equals(plan.status())) {
            return;
        }
        plans.put(planId, plan.withStatus(
            accepted ? OperationPlanStatus.ROUTE_ACCEPTED : OperationPlanStatus.ROUTE_REJECTED,
            accepted ? null : rejectReason,
            simulatedAt == null ? Instant.now() : simulatedAt
        ));
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
