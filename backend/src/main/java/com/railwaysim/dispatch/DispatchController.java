package com.railwaysim.dispatch;

import com.railwaysim.dispatch.command.CommandStatus;
import com.railwaysim.dispatch.disturbance.DisturbanceEvent;
import com.railwaysim.dispatch.monitor.StationRecordStore;
import com.railwaysim.dispatch.monitor.TrainStationEvent;
import com.railwaysim.dispatch.operation.OperationPlan;
import com.railwaysim.dispatch.operation.OperationPlanRequest;
import com.railwaysim.dispatch.operation.OperationRouteCandidate;
import com.railwaysim.dispatch.operation.OperationRouteTemplate;
import com.railwaysim.dispatch.plan.CurrentRunPlan;
import com.railwaysim.dispatch.plan.DispatchPlanFile.SegmentEntry;
import com.railwaysim.dispatch.plan.OperationPlanLoader;
import com.railwaysim.dispatch.plan.RunModePeriod;
import com.railwaysim.signal.RouteInterlockingService;
import com.railwaysim.signal.RouteInterlockingService.RouteInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dispatch")
@CrossOrigin
public class DispatchController {

    private final DispatchService dispatchService;
    private final OperationPlanLoader planLoader;
    private final StationRecordStore stationRecordStore;
    private final RouteInterlockingService routeInterlockingService;

    public DispatchController(
        DispatchService dispatchService,
        OperationPlanLoader planLoader,
        StationRecordStore stationRecordStore,
        RouteInterlockingService routeInterlockingService
    ) {
        this.dispatchService = dispatchService;
        this.planLoader = planLoader;
        this.stationRecordStore = stationRecordStore;
        this.routeInterlockingService = routeInterlockingService;
    }

    @GetMapping("/plan")
    public PlanResponse plan() {
        List<PeriodView> periods = planLoader.periods().stream()
            .map(PeriodView::from)
            .toList();
        return new PlanResponse(
            planLoader.planId(),
            planLoader.lineId(),
            periods,
            planLoader.stations(),
            planLoader.segments(),
            planLoader.circulations(),
            planLoader.services()
        );
    }

    @GetMapping("/plan/current")
    public CurrentRunPlan currentPlan() {
        return dispatchService.currentPlan();
    }

    @GetMapping("/status")
    public DispatchSnapshot status() {
        return dispatchService.snapshot();
    }

    @GetMapping("/disturbances")
    public List<DisturbanceEvent> disturbances() {
        return dispatchService.disturbances();
    }

    @GetMapping("/commands")
    public List<DispatchCommand> commands() {
        return dispatchService.commands();
    }

    @PostMapping("/commands")
    public DispatchCommand submit(@Valid @RequestBody ManualCommandRequest request) {
        Map<String, Object> payload = request.payload() == null
            ? new HashMap<>()
            : new HashMap<>(request.payload());
        if (request.detail() != null && !request.detail().isBlank()) {
            payload.put("detail", request.detail());
        }
        if (request.routeId() != null && !request.routeId().isBlank()) {
            payload.put("routeId", request.routeId());
        }
        if (request.targetHeadwaySec() != null) {
            payload.put("targetHeadwaySec", request.targetHeadwaySec());
        }
        if (request.speedBiasRatio() != null) {
            payload.put("speedBiasRatio", request.speedBiasRatio());
        }
        if (request.deltaDwellSec() != null) {
            payload.put("deltaDwellSec", request.deltaDwellSec());
        }
        DispatchCommand command = new DispatchCommand(
            "DC-" + UUID.randomUUID(),
            request.trainId(),
            request.commandType(),
            payload,
            "MANUAL",
            CommandStatus.PENDING,
            Instant.now(),
            null
        );
        return dispatchService.submit(command);
    }

    @PostMapping("/commands/{commandId}/cancel")
    public Map<String, Object> cancel(@PathVariable String commandId) {
        dispatchService.cancelCommand(commandId);
        return Map.of("accepted", true, "commandId", commandId);
    }

    // ---- 调度进路查询 ----

    @GetMapping("/route/list")
    public List<RouteInfo> routeList() {
        return routeInterlockingService.queryRoutes();
    }

    @PostMapping("/route/establish")
    public RouteEstablishResponse establishRoute(
        @RequestParam String routeId,
        @RequestParam String trainId
    ) {
        DispatchCommand command = submit(new ManualCommandRequest(
            trainId,
            "REQUEST_ROUTE",
            routeId,
            null,
            null,
            null,
            routeId,
            Map.of("source", "MANUAL_ROUTE_ESTABLISH")
        ));
        return new RouteEstablishResponse(true, routeId, trainId, null, command.id());
    }

    @GetMapping("/operation-route/templates")
    public List<OperationRouteTemplate> operationRouteTemplates() {
        return dispatchService.operationRouteTemplates();
    }

    @PostMapping("/operation-route/preview")
    public List<OperationRouteCandidate> previewOperationRoute(@RequestBody OperationPlanRequest request) {
        return dispatchService.previewOperationPlan(request);
    }

    @GetMapping("/operation-plans")
    public List<OperationPlan> operationPlans() {
        return dispatchService.operationPlans();
    }

    @PostMapping("/operation-plans")
    public OperationPlan createOperationPlan(@RequestBody OperationPlanRequest request) {
        return dispatchService.createOperationPlan(request);
    }

    @PostMapping("/operation-plans/{planId}/cancel")
    public OperationPlan cancelOperationPlan(@PathVariable String planId) {
        return dispatchService.cancelOperationPlan(planId);
    }

    @PostMapping("/headway/adjust")
    public DispatchCommand adjustHeadway(@Valid @RequestBody HeadwayAdjustRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("targetHeadwaySec", request.targetHeadwaySec());
        payload.put("regulatedTrainId", request.trainId());
        payload.put("regulationAction", request.regulationAction() == null || request.regulationAction().isBlank()
            ? "OBSERVE"
            : request.regulationAction());
        payload.put("regulationSource", "MANUAL_HEADWAY_ADJUST");
        if (request.frontTrainId() != null && !request.frontTrainId().isBlank()) {
            payload.put("frontTrainId", request.frontTrainId());
        }
        return submit(new ManualCommandRequest(
            request.trainId(),
            "HEADWAY_ADJUST",
            null,
            request.targetHeadwaySec(),
            null,
            null,
            null,
            payload
        ));
    }

    @GetMapping("/station-records")
    public List<TrainStationEvent> stationRecords() {
        return stationRecordStore.list(dispatchService.simulationRunId());
    }

    public record ManualCommandRequest(
        @NotBlank String trainId,
        @NotBlank String commandType,
        String detail,
        Integer targetHeadwaySec,
        Double speedBiasRatio,
        Integer deltaDwellSec,
        String routeId,
        Map<String, Object> payload
    ) {
    }

    public record RouteEstablishResponse(
        boolean accepted,
        String routeId,
        String trainId,
        String rejectReason,
        String commandId
    ) {
    }

    public record HeadwayAdjustRequest(
        @NotBlank String trainId,
        int targetHeadwaySec,
        String regulationAction,
        String frontTrainId
    ) {
        public HeadwayAdjustRequest {
            if (targetHeadwaySec < 30 || targetHeadwaySec > 900) {
                throw new IllegalArgumentException("targetHeadwaySec must be between 30 and 900");
            }
        }
    }

    public record PlanResponse(
        String planId,
        String lineId,
        List<PeriodView> periods,
        List<com.railwaysim.dispatch.monitor.StationInfo> stations,
        List<SegmentEntry> segments,
        List<com.railwaysim.dispatch.plan.CirculationPlan> circulations,
        List<com.railwaysim.dispatch.plan.TrainServicePlan> services
    ) {
    }

    public record PeriodView(
        String periodType,
        String start,
        String end,
        int departureIntervalSec,
        int defaultDwellTimeSec
    ) {
        static PeriodView from(RunModePeriod period) {
            return new PeriodView(
                period.periodType(),
                period.startTime().toString(),
                period.endTime().toString(),
                period.departureIntervalSec(),
                period.defaultDwellTimeSec()
            );
        }
    }
}
