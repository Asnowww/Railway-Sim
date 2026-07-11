package com.railwaysim.api;

import com.railwaysim.signal.RouteInterlockingService;
import com.railwaysim.signal.SignalTrackFaultType;
import com.railwaysim.track.TrackService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 信号轨道专用调试/演示接口（WP-05 + WP-07 收口）。
 *
 * <p>包括故障注入、故障查询、告警确认、进路生命周期查询。
 * 所有写操作都包含 operator + traceId 审计字段。
 */
@RestController
@RequestMapping("/api/signal-track")
@CrossOrigin
public class SignalTrackController {

    private final TrackService trackService;
    private final RouteInterlockingService routeInterlockingService;

    public SignalTrackController(TrackService trackService, RouteInterlockingService routeInterlockingService) {
        this.trackService = trackService;
        this.routeInterlockingService = routeInterlockingService;
    }

    /** GET /api/signal-track/routes — 当前所有进路状态 */
    @GetMapping("/routes")
    public List<RouteInterlockingService.RouteInfo> routes() {
        return routeInterlockingService.queryRoutes();
    }

    /** GET /api/signal-track/routes/{routeId}/status — 单条进路详细状态 */
    @GetMapping("/routes/{routeId}/status")
    public Map<String, Object> routeStatus(@PathVariable String routeId) {
        return Map.of(
            "routeId", routeId,
            "status", routeInterlockingService.state(routeId).status().name()
        );
    }

    /** POST /api/signal-track/faults — 注入故障（WP-05） */
    @PostMapping("/faults")
    public Map<String, Object> injectFault(@RequestBody Map<String, Object> body) {
        String sourceId = (String) body.getOrDefault("sourceId", "");
        String faultType = (String) body.getOrDefault("faultType", "TRACK_CIRCUIT_OCCUPIED");
        String operator = (String) body.getOrDefault("operator", "system");
        String reason = (String) body.getOrDefault("reason", "");
        String traceId = (String) body.getOrDefault("traceId", "");

        trackService.injectFault(sourceId);
        SignalTrackFaultType type;
        try { type = SignalTrackFaultType.valueOf(faultType); }
        catch (IllegalArgumentException e) { type = SignalTrackFaultType.TRACK_CIRCUIT_OCCUPIED; }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("sourceId", sourceId);
        result.put("faultType", type.name());
        result.put("operator", operator);
        result.put("reason", reason);
        result.put("traceId", traceId);
        return result;
    }

    /** POST /api/signal-track/faults/{faultId}/clear */
    @PostMapping("/faults/{faultId}/clear")
    public Map<String, Object> clearFault(@PathVariable String faultId,
                                          @RequestParam(defaultValue = "system") String operator,
                                          @RequestParam(defaultValue = "") String traceId) {
        trackService.clearFault(faultId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("faultId", faultId);
        result.put("operator", operator);
        result.put("traceId", traceId);
        return result;
    }

    /** GET /api/signal-track/faults — 当前所有故障区段 */
    @GetMapping("/faults")
    public List<String> faults() {
        return trackService.faultSegmentIds().stream().toList();
    }
}
