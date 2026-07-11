package com.railwaysim.api;

import com.railwaysim.api.dto.SignalTrackFaultRequest;
import com.railwaysim.signal.RouteInterlockingService;
import com.railwaysim.signal.SignalTrackFaultType;
import com.railwaysim.track.TrackService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 信号轨道专用调试/演示接口（WP-05 + WP-07 收口）。
 *
 * <p>包括故障注入、故障查询、告警确认、进路生命周期查询。
 * 所有写操作都包含 operator + traceId 审计字段。
 */
@RestController
@RequestMapping("/api/signal-track")
@CrossOrigin
@Validated
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
    public FaultMutationResponse injectFault(@Valid @RequestBody SignalTrackFaultRequest request) {
        requireSegment(request.sourceId());
        boolean changed = trackService.injectFault(request.sourceId());
        return new FaultMutationResponse(
            true,
            changed,
            !changed,
            "INJECT",
            request.sourceId(),
            request.faultType(),
            request.normalizedOperator(),
            request.normalizedReason(),
            request.normalizedTraceId()
        );
    }

    /** POST /api/signal-track/faults/{segmentId}/clear */
    @PostMapping("/faults/{segmentId}/clear")
    public FaultMutationResponse clearFault(
        @PathVariable @NotBlank String segmentId,
        @RequestParam(defaultValue = "system") String operator,
        @RequestParam(defaultValue = "") String reason,
        @RequestParam(defaultValue = "") String traceId
    ) {
        requireSegment(segmentId);
        boolean changed = trackService.clearFault(segmentId);
        return new FaultMutationResponse(
            true,
            changed,
            !changed,
            "CLEAR",
            segmentId,
            null,
            operator == null || operator.isBlank() ? "system" : operator,
            reason == null ? "" : reason,
            traceId == null ? "" : traceId
        );
    }

    /** GET /api/signal-track/faults — 当前所有故障区段 */
    @GetMapping("/faults")
    public List<String> faults() {
        return trackService.faultSegmentIds().stream().sorted().toList();
    }

    private void requireSegment(String segmentId) {
        if (!trackService.segmentExists(segmentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Track segment " + segmentId + " does not exist");
        }
    }

    public record FaultMutationResponse(
        boolean accepted,
        boolean changed,
        boolean idempotent,
        String operation,
        String sourceId,
        SignalTrackFaultType faultType,
        String operator,
        String reason,
        String traceId
    ) {
    }
}
