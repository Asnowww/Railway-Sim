package com.railwaysim.dispatch;

import com.railwaysim.dispatch.command.CommandStatus;
import com.railwaysim.dispatch.disturbance.DisturbanceEvent;
import com.railwaysim.dispatch.disturbance.DisturbanceType;
import com.railwaysim.dispatch.monitor.StationRecordStore;
import com.railwaysim.dispatch.monitor.TrainStationEvent;
import com.railwaysim.dispatch.operation.OperationPlan;
import com.railwaysim.dispatch.operation.OperationPlanRequest;
import com.railwaysim.dispatch.operation.OperationRouteCandidate;
import com.railwaysim.dispatch.operation.OperationRouteTemplate;
import com.railwaysim.dispatch.operation.CirculationPlanRequest;
import com.railwaysim.dispatch.operation.TrainCirculationPlan;
import com.railwaysim.dispatch.plan.CurrentRunPlan;
import com.railwaysim.dispatch.plan.DispatchPlanFile.SegmentEntry;
import com.railwaysim.dispatch.plan.OperationPlanLoader;
import com.railwaysim.dispatch.plan.RunModePeriod;
import com.railwaysim.signal.RouteInterlockingService;
import com.railwaysim.signal.RouteInterlockingService.RouteInfo;
import com.railwaysim.signal.dispatch.SignalDispatchPlanPublication;
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

    @PostMapping("/disturbances/demo")
    public DisturbanceEvent injectDemoDisturbance(@RequestBody DemoDisturbanceRequest request) {
        return dispatchService.injectDemoDisturbance(
            request.trainId(),
            request.type(),
            request.headwayDirection(),
            request.targetHeadwaySec(),
            request.actualHeadwaySec(),
            request.violationSec(),
            request.stationId()
        );
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

    @PostMapping("/command-feedback")
    public CommandFeedbackResponse acceptCommandFeedback(
        @RequestBody(required = false) List<CommandFeedbackRequest> requests
    ) {
        List<CommandFeedbackRequest> safeRequests = requests == null ? List.of() : requests;
        List<DispatchCommandFeedback> feedbacks = safeRequests.stream()
            .filter(request -> request.commandId() != null && !request.commandId().isBlank())
            .map(CommandFeedbackRequest::toFeedback)
            .toList();
        List<DispatchCommand> updated = dispatchService.acceptFeedback(feedbacks);
        return new CommandFeedbackResponse(
            safeRequests.size(),
            updated.size(),
            Math.max(0, safeRequests.size() - updated.size()),
            updated
        );
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

    @GetMapping("/circulation-plans")
    public List<TrainCirculationPlan> circulationPlans() {
        return dispatchService.circulationPlans();
    }

    @PostMapping("/circulation-plans/auto")
    public List<TrainCirculationPlan> autoAssignCirculationPlans(
        @RequestBody(required = false) CirculationPlanRequest request
    ) {
        return dispatchService.autoAssignCirculationPlans(request);
    }

    @PostMapping("/circulation-plans/{circulationId}/cancel")
    public TrainCirculationPlan cancelCirculationPlan(@PathVariable String circulationId) {
        return dispatchService.cancelCirculationPlan(circulationId);
    }

    @PostMapping("/signal-publications")
    public SignalDispatchPlanPublication publishSignalPlan(
        @RequestBody(required = false) SignalPlanPublicationRequest request
    ) {
        return dispatchService.publishPlanToSignal(
            request == null ? null : request.operator(),
            request == null ? null : request.effectiveFrom()
        );
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

    public record CommandFeedbackRequest(
        @NotBlank String commandId,
        String trainId,
        String commandType,
        String feedbackSource,
        @NotBlank String feedbackStatus,
        String reason,
        Instant observedAt,
        Instant feedbackAt,
        Map<String, Object> details
    ) {
        DispatchCommandFeedback toFeedback() {
            return new DispatchCommandFeedback(
                commandId,
                trainId,
                commandType,
                feedbackSource == null || feedbackSource.isBlank() ? "SIGNAL_RUNTIME" : feedbackSource,
                feedbackStatus == null ? null : feedbackStatus.trim().toUpperCase(),
                reason,
                observedAt == null ? feedbackAt : observedAt,
                details
            );
        }
    }

    public record CommandFeedbackResponse(
        int receivedCount,
        int updatedCount,
        int ignoredCount,
        List<DispatchCommand> commands
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

    public record DemoDisturbanceRequest(
        String trainId,
        DisturbanceType type,
        String headwayDirection,
        Double targetHeadwaySec,
        Double actualHeadwaySec,
        Double violationSec,
        String stationId
    ) {
    }

    public record SignalPlanPublicationRequest(
        String operator,
        Instant effectiveFrom
    ) {
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
