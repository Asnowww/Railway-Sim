package com.railwaysim.api;

import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.track.TrackOccupancy;
import com.railwaysim.track.TrackPositionResolver;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.track.TrackService;
import com.railwaysim.track.TrainTrackPosition;
import com.railwaysim.train.TrainManager;
import com.railwaysim.signal.RouteInterlockingService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 轨道拓扑与位置查询接口（WP-02）。
 */
@RestController
@RequestMapping("/api")
@CrossOrigin
public class TrackController {

    private final TrackService trackService;
    private final TrackPositionResolver positionResolver;
    private final TrainManager trainManager;
    private final StaticInfrastructureCatalog catalog;
    private final RouteInterlockingService routeInterlockingService;

    public TrackController(TrackService trackService, TrackPositionResolver positionResolver,
                           TrainManager trainManager, StaticInfrastructureCatalog catalog,
                           RouteInterlockingService routeInterlockingService) {
        this.trackService = trackService;
        this.positionResolver = positionResolver;
        this.trainManager = trainManager;
        this.catalog = catalog;
        this.routeInterlockingService = routeInterlockingService;
    }

    /** GET /api/infrastructure/topology — 线路拓扑全貌 */
    @GetMapping("/infrastructure/topology")
    public Map<String, Object> topology() {
        OperationalLineData line = catalog.lineData();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lineId", line.lineId());
        result.put("lineName", line.lineName());
        result.put("lengthMeters", line.lineLengthMeters());
        result.put("stations", line.stations().stream().map(s -> {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", s.id()); sm.put("name", s.name()); sm.put("centerMeters", s.centerMeters());
            // 双向站台（9号线：上下行站中心/停车窗口/站台侧不同）
            List<Map<String, Object>> platformList = line.platforms().stream()
                .filter(p -> s.platformIds().contains(p.id()))
                .map(p -> {
                    Map<String, Object> pm = new LinkedHashMap<>();
                    pm.put("id", p.id());
                    pm.put("track", p.directionCode() == null ? "main" : p.directionCode().toLowerCase(java.util.Locale.ROOT));
                    pm.put("centerMeters", p.centerMeters());
                    pm.put("stopLeftMeters", p.stopLeftMeters());
                    pm.put("stopRightMeters", p.stopRightMeters());
                    pm.put("side", p.platformSide());
                    pm.put("anchorSegmentId", p.anchorSegmentId());
                    return pm;
                })
                .toList();
            if (!platformList.isEmpty()) {
                sm.put("platforms", platformList);
            }
            return sm;
        }).toList());
        // 视景边号映射（UDP segNo，来自线路数据 raw_segment_id）
        Map<String, Integer> rawIdBySegment = new LinkedHashMap<>();
        line.trackSegments().forEach(seg -> rawIdBySegment.put(seg.id(), seg.rawSegmentId()));
        result.put("segments", trackService.states().stream().map(s -> {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", s.id()); sm.put("startMeters", s.startMeters()); sm.put("endMeters", s.endMeters());
            sm.put("speedLimitMetersPerSecond", s.speedLimitMetersPerSecond());
            sm.put("occupancy", s.occupancy().name());
            sm.put("fromNode", s.fromNode()); sm.put("toNode", s.toNode()); sm.put("track", s.track());
            Integer rawId = rawIdBySegment.get(s.id());
            if (rawId != null && rawId > 0) {
                sm.put("rawSegmentId", rawId);
            }
            return sm;
        }).toList());
        result.put("switches", trackService.switchStates());
        result.put("routes", routeInterlockingService.queryRoutes());
        result.put("forwardNeighbors", trackService.forwardNeighborMap());
        return result;
    }

    /** GET /api/track/segments — 所有区段状态 */
    @GetMapping("/track/segments")
    public List<TrackSegmentState> segments() {
        return trackService.states();
    }

    /** GET /api/track/segments/{segmentId} — 单个区段 */
    @GetMapping("/track/segments/{segmentId}")
    public TrackSegmentState segment(@PathVariable String segmentId) {
        return trackService.states().stream()
            .filter(s -> s.id().equals(segmentId))
            .findFirst()
            .orElse(null);
    }

    /** GET /api/track/position — 按公里标查区段和拓扑位置 */
    @GetMapping("/track/position")
    public Map<String, Object> position(@RequestParam double mileage,
                                        @RequestParam(defaultValue = "FORWARD") String direction) {
        TrackSegmentState seg = trackService.segmentAt(mileage);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mileage", mileage);
        result.put("direction", direction);
        if (seg == null) {
            result.put("error", "NO_SEGMENT_FOUND");
            return result;
        }
        result.put("segmentId", seg.id());
        result.put("startMeters", seg.startMeters());
        result.put("endMeters", seg.endMeters());
        result.put("offsetMeters", Math.max(0, mileage - seg.startMeters()));
        result.put("track", seg.track());
        result.put("occupancy", seg.occupancy().name());
        return result;
    }

    /** GET /api/track/positions — 全部列车拓扑位置 */
    @GetMapping("/track/positions")
    public List<TrainTrackPosition> positions() {
        return positionResolver.resolveAll(trainManager.states());
    }
}
