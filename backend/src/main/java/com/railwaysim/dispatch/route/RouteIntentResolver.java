package com.railwaysim.dispatch.route;

import com.railwaysim.dispatch.config.DispatchProperties;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.train.TrainState;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class RouteIntentResolver {

    private final RouteCatalog routeCatalog;
    private final DispatchProperties properties;

    public RouteIntentResolver(RouteCatalog routeCatalog, DispatchProperties properties) {
        this.routeCatalog = routeCatalog;
        this.properties = properties;
    }

    public List<TrainRouteIntent> resolve(
        Instant simulatedAt,
        List<TrainState> trains,
        List<MovementAuthority> authorities
    ) {
        if (!properties.isRouteDispatchEnabled() || trains == null || trains.isEmpty()) {
            return List.of();
        }
        Map<String, MovementAuthority> authorityByTrain = new HashMap<>();
        if (authorities != null) {
            for (MovementAuthority authority : authorities) {
                authorityByTrain.put(authority.trainId(), authority);
            }
        }
        return trains.stream()
            .map(train -> resolveOne(simulatedAt, train, authorityByTrain.get(train.id())))
            .flatMap(Optional::stream)
            .toList();
    }

    private Optional<TrainRouteIntent> resolveOne(
        Instant simulatedAt,
        TrainState train,
        MovementAuthority authority
    ) {
        if (train == null || train.id() == null || train.id().isBlank()) {
            return Optional.empty();
        }
        double observedSpeed = Math.max(0, train.speedMetersPerSecond());
        double planningSpeed = observedSpeed > properties.getStopSpeedThresholdMps()
            ? observedSpeed
            : properties.getBaseCruiseSpeedMps();
        double lookaheadMeters = Math.max(
            properties.getRouteApproachWindowMeters(),
            planningSpeed * properties.getRouteApproachLookaheadSeconds()
        );
        String currentSegmentId = authority == null ? null : authority.currentSegmentId();

        return routeCatalog.candidateRoutesNear(train.positionMeters(), currentSegmentId, lookaheadMeters).stream()
            .filter(route -> directionCompatible(train, route))
            .min(Comparator
                .comparing((DispatchRouteCandidate route) -> !route.routeId().equals(train.routeId()))
                .thenComparing(route -> !route.mainline())
                .thenComparingDouble(route -> Math.max(0, route.entryMeters() - train.positionMeters()))
                .thenComparingDouble(DispatchRouteCandidate::lengthMeters)
                .thenComparing(DispatchRouteCandidate::routeId))
            .map(route -> intentFor(simulatedAt, train, route, planningSpeed));
    }

    private TrainRouteIntent intentFor(
        Instant simulatedAt,
        TrainState train,
        DispatchRouteCandidate route,
        double planningSpeed
    ) {
        double distance = Math.max(0, route.entryMeters() - train.positionMeters());
        double etaSeconds = planningSpeed <= 0 ? 0 : distance / planningSpeed;
        double priority = route.mainline() ? 10 : 5;
        priority += Math.max(0, properties.getRouteApproachWindowMeters() - distance) / 100.0;
        String reason = "AUTO_ROUTE_APPROACH(route=" + route.routeId()
            + ",distance=" + Math.round(distance)
            + "m,eta=" + Math.round(etaSeconds) + "s)";
        return new TrainRouteIntent(
            train.id(),
            route.routeId(),
            reason,
            distance,
            etaSeconds,
            priority,
            0,
            simulatedAt,
            simulatedAt.plusSeconds(Math.max(5, properties.getRouteIntentValiditySeconds()))
        );
    }

    private boolean directionCompatible(TrainState train, DispatchRouteCandidate route) {
        // 引擎当前仅支持里程递增运行（TrainState.direction 的 UP/DOWN 是车辆协议
        // 标签，初始车即为 DOWN 但实际里程递增；见 line-m9.yaml 约定）。
        // 若按标签把 DOWN 当反向，会给上行运行的车自动请求 R_DOWN/折返进路，
        // 导致列车被绑定到对向股道、占用染色跨轨。故只接受前向（exit>=entry）进路。
        return route.exitMeters() >= route.entryMeters();
    }
}
