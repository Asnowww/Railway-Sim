package com.railwaysim.dispatch;

import com.railwaysim.dispatch.disturbance.DisturbanceEvent;
import com.railwaysim.dispatch.monitor.StationRecordStore;
import com.railwaysim.dispatch.monitor.TrainStationEvent;
import com.railwaysim.dispatch.plan.CurrentRunPlan;
import com.railwaysim.dispatch.plan.OperationPlanLoader;
import com.railwaysim.dispatch.plan.RunModePeriod;
import java.util.List;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/station-records")
    public List<TrainStationEvent> stationRecords() {
        return stationRecordStore.list(dispatchService.simulationRunId());
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
