package com.railwaysim.api;

import com.railwaysim.api.dto.FaultMutationRequest;
import com.railwaysim.api.dto.OperationLogEntry;
import com.railwaysim.api.dto.TrainEnergyResponse;
import com.railwaysim.train.TrainFaultRecord;
import com.railwaysim.train.TrainManager;
import com.railwaysim.train.TrainState;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/trains")
@CrossOrigin
public class TrainController {

    private static final String CONFIRM_TOKEN = "SIMULATION_CONFIRM";

    private final TrainManager trainManager;
    private final ApiOperationLogService operationLogService;

    public TrainController(TrainManager trainManager, ApiOperationLogService operationLogService) {
        this.trainManager = trainManager;
        this.operationLogService = operationLogService;
    }

    @GetMapping
    public List<TrainState> trains() {
        return trainManager.states();
    }

    @GetMapping("/{trainId}")
    public TrainState train(@PathVariable String trainId) {
        return trainManager.state(trainId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Train not found: " + trainId));
    }

    @GetMapping("/{trainId}/energy")
    public TrainEnergyResponse energy(@PathVariable String trainId) {
        TrainState train = train(trainId);
        return new TrainEnergyResponse(
            train.id(),
            train.energyConsumedKwh(),
            train.energyRegeneratedKwh(),
            train.energyConsumedKwh() - train.energyRegeneratedKwh(),
            "CURRENT_SIMULATION",
            train.dataQuality(),
            Instant.now()
        );
    }

    @GetMapping("/{trainId}/faults")
    public List<TrainFaultRecord> faults(@PathVariable String trainId) {
        train(trainId);
        return trainManager.faultRecords(trainId);
    }

    @PostMapping("/{trainId}/faults")
    public TrainFaultRecord injectFault(
        @PathVariable String trainId,
        @RequestBody FaultMutationRequest request
    ) {
        requireConfirm(request);
        String beforeState = train(trainId).faultCode();
        TrainFaultRecord record = trainManager.injectFault(
            trainId,
            requiredFaultType(request),
            request.normalizedReason(),
            request.normalizedTraceId()
        );
        OperationLogEntry operation = operationLogService.record(
            request.normalizedOperator(),
            "TRAIN_FAULT_INJECT",
            "train:" + trainId,
            beforeState,
            record.faultCode(),
            request.normalizedReason(),
            request.normalizedTraceId()
        );
        operationLogService.recordTrainFault(operation, trainId, record.faultCode(), record.faultLevel(), "INJECTED");
        return record;
    }

    @PostMapping("/{trainId}/faults/clear")
    public TrainFaultRecord clearFault(
        @PathVariable String trainId,
        @RequestBody FaultMutationRequest request
    ) {
        requireConfirm(request);
        String beforeState = train(trainId).faultCode();
        TrainFaultRecord record = trainManager.clearFault(trainId, request.normalizedReason(), request.normalizedTraceId());
        operationLogService.record(
            request.normalizedOperator(),
            "TRAIN_FAULT_CLEAR",
            "train:" + trainId,
            beforeState,
            record.state(),
            request.normalizedReason(),
            request.normalizedTraceId()
        );
        return record;
    }

    private void requireConfirm(FaultMutationRequest request) {
        if (request == null || !CONFIRM_TOKEN.equals(request.confirmToken())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirmToken must be SIMULATION_CONFIRM");
        }
    }

    private String requiredFaultType(FaultMutationRequest request) {
        if (request.faultType() == null || request.faultType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "faultType is required");
        }
        return request.faultType();
    }
}
