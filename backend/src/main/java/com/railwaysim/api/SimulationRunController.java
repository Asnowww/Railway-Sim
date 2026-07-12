package com.railwaysim.api;

import com.railwaysim.simulation.SimulationRunRecord;
import com.railwaysim.simulation.SimulationRunService;
import com.railwaysim.simulation.TrainStopStatistics;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/simulation-runs")
public class SimulationRunController {

    private final SimulationRunService runService;

    public SimulationRunController(SimulationRunService runService) {
        this.runService = runService;
    }

    @GetMapping
    public List<SimulationRunRecord> list(@RequestParam(defaultValue = "50") int limit) {
        return runService.list(limit);
    }

    @GetMapping("/{runId}")
    public SimulationRunRecord detail(@PathVariable String runId) {
        return runService.find(runId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation run not found: " + runId));
    }

    @GetMapping("/{runId}/summary")
    public Map<String, Object> summary(@PathVariable String runId) {
        return runService.summary(runId);
    }

    @GetMapping("/{runId}/train-snapshots")
    public List<Map<String, Object>> trainSnapshots(@PathVariable String runId,
        @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        return runService.trainSnapshots(runId, limit, offset);
    }

    @GetMapping("/{runId}/control-decisions")
    public List<Map<String, Object>> controlDecisions(@PathVariable String runId,
        @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        return runService.controlDecisions(runId, limit, offset);
    }

    @GetMapping("/{runId}/power-snapshots")
    public List<Map<String, Object>> powerSnapshots(@PathVariable String runId,
        @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        return runService.powerSnapshots(runId, limit, offset);
    }

    @GetMapping("/{runId}/stop-results")
    public List<Map<String, Object>> stopResults(@PathVariable String runId,
        @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        return runService.stopResults(runId, limit, offset);
    }

    @GetMapping("/{runId}/stop-statistics")
    public TrainStopStatistics stopStatistics(
        @PathVariable String runId,
        @RequestParam(required = false) String trainId,
        @RequestParam(required = false) String stationId,
        @RequestParam(defaultValue = "10") int requiredSampleCount
    ) {
        return runService.stopStatistics(runId, trainId, stationId, requiredSampleCount);
    }

    @GetMapping("/{runId}/faults")
    public List<Map<String, Object>> faults(@PathVariable String runId,
        @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        return runService.faults(runId, limit, offset);
    }
}
