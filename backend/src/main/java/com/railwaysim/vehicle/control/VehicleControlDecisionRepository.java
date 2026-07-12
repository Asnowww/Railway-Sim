package com.railwaysim.vehicle.control;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 车辆控制决策存储 — 内存实现，支持按 trainId/runId/tick 查询。
 */
@Service
/** @deprecated LOCAL mode only. */
@Deprecated(forRemoval=true, since="2.0")
public class VehicleControlDecisionRepository {

    private final Map<String, List<VehicleControlDecision>> decisionsByTrain = new ConcurrentHashMap<>();
    private final Map<String, List<VehicleControlDecision>> decisionsByRun = new ConcurrentHashMap<>();

    public void store(VehicleControlDecision decision) {
        decisionsByTrain.computeIfAbsent(decision.trainId(), k -> 
            Collections.synchronizedList(new ArrayList<>())
        ).add(decision);
        
        decisionsByRun.computeIfAbsent(decision.runId(), k ->
            Collections.synchronizedList(new ArrayList<>())
        ).add(decision);
    }

    public VehicleControlDecision latest(String trainId) {
        List<VehicleControlDecision> decisions = decisionsByTrain.get(trainId);
        if (decisions == null || decisions.isEmpty()) {
            return null;
        }
        return decisions.get(decisions.size() - 1);
    }

    public List<VehicleControlDecision> byTrainAndRun(String trainId, String runId) {
        List<VehicleControlDecision> runDecisions = decisionsByRun.get(runId);
        if (runDecisions == null) {
            return List.of();
        }
        return runDecisions.stream()
            .filter(d -> d.trainId().equals(trainId))
            .toList();
    }

    public List<VehicleControlDecision> byRunAndTick(String runId, long tick) {
        List<VehicleControlDecision> runDecisions = decisionsByRun.get(runId);
        if (runDecisions == null) {
            return List.of();
        }
        return runDecisions.stream()
            .filter(d -> d.tick() == tick)
            .toList();
    }

    public void clear() {
        decisionsByTrain.clear();
        decisionsByRun.clear();
    }
}
