package com.railwaysim.dispatch;

import com.railwaysim.dispatch.command.CommandStatus;
import com.railwaysim.dispatch.disturbance.DisturbanceEvent;
import com.railwaysim.dispatch.monitor.StationRecordStore;
import com.railwaysim.dispatch.monitor.TrainStationEvent;
import com.railwaysim.dispatch.plan.CurrentRunPlan;
import com.railwaysim.dispatch.plan.OperationPlanLoader;
import com.railwaysim.dispatch.plan.RunModePeriod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    public DispatchController(
        DispatchService dispatchService,
        OperationPlanLoader planLoader,
        StationRecordStore stationRecordStore
    ) {
        this.dispatchService = dispatchService;
        this.planLoader = planLoader;
        this.stationRecordStore = stationRecordStore;
    }

    @GetMapping("/plan")
    public PlanResponse plan() {
        List<PeriodView> periods = planLoader.periods().stream()
            .map(PeriodView::from)
            .toList();
        return new PlanResponse(planLoader.planId(), planLoader.lineId(), periods);
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
        Map<String, Object> payload = new HashMap<>();
        if (request.detail() != null && !request.detail().isBlank()) {
            payload.put("detail", request.detail());
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
        dispatchService.submit(command);
        return command;
    }

    @GetMapping("/station-records")
    public List<TrainStationEvent> stationRecords() {
        return stationRecordStore.list(dispatchService.simulationRunId());
    }

    public record ManualCommandRequest(
        @NotBlank String trainId,
        @NotBlank String commandType,
        String detail
    ) {
    }

    public record PlanResponse(String planId, String lineId, List<PeriodView> periods) {
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
