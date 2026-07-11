package com.railwaysim.api;

import com.railwaysim.api.dto.ApiError;
import com.railwaysim.api.dto.VehicleControlDecisionResponse;
import com.railwaysim.vehicle.control.VehicleControlDecisionRepository;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vehicle/control-decisions")
public class VehicleControlDecisionController {

    private final VehicleControlDecisionRepository repository;

    public VehicleControlDecisionController(VehicleControlDecisionRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{trainId}/latest")
    public ResponseEntity<?> latest(@PathVariable String trainId) {
        var decision = repository.latest(trainId);
        if (decision == null) {
            return ResponseEntity.status(404)
                .body(ApiError.notFound("No decision found for train " + trainId));
        }
        return ResponseEntity.ok(VehicleControlDecisionResponse.from(decision));
    }

    @GetMapping("/{trainId}/history")
    public ResponseEntity<?> history(
        @PathVariable String trainId,
        @RequestParam(defaultValue = "") String runId
    ) {
        if (runId.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(ApiError.badRequest("runId is required"));
        }
        List<VehicleControlDecisionResponse> decisions = repository.byTrainAndRun(trainId, runId)
            .stream()
            .map(VehicleControlDecisionResponse::from)
            .collect(Collectors.toList());
        return ResponseEntity.ok(decisions);
    }
}
