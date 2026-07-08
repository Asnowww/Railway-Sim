package com.railwaysim.api;

import com.railwaysim.api.dto.FaultMutationRequest;
import com.railwaysim.api.dto.OperationLogEntry;
import com.railwaysim.api.dto.TrainEnergyResponse;
import com.railwaysim.api.dto.TrainLifecycleCommandRequest;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleAction;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleCommand;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleTrainSpec;
import com.railwaysim.train.TrainFaultRecord;
import com.railwaysim.train.TrainManager;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.external.ExternalTrainDirection;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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

    @PostMapping("/lifecycle")
    public List<TrainState> applyLifecycle(@RequestBody TrainLifecycleCommandRequest request) {
        requireConfirm(request);
        SignalTrainLifecycleCommand command = toLifecycleCommand(request);
        List<TrainState> beforeStates = trainManager.states();
        List<TrainState> afterStates = trainManager.applyLifecycleCommand(command);
        operationLogService.record(
            request.normalizedOperator(),
            "TRAIN_EXTERNAL_CONTROL_LIFECYCLE",
            "trains:lifecycle",
            "count=" + beforeStates.size(),
            command.action().name() + ",count=" + afterStates.size(),
            request.normalizedReason(),
            request.normalizedTraceId()
        );
        return afterStates;
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

    private void requireConfirm(TrainLifecycleCommandRequest request) {
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

    private SignalTrainLifecycleCommand toLifecycleCommand(TrainLifecycleCommandRequest request) {
        SignalTrainLifecycleAction action = parseAction(request.action());
        if (action == SignalTrainLifecycleAction.CLEAR) {
            return SignalTrainLifecycleCommand.clear();
        }
        List<SignalTrainLifecycleTrainSpec> trains = request.normalizedTrains().stream()
            .map(train -> toTrainSpec(action, train))
            .toList();
        if (trains.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "trains are required for ADD/DELETE");
        }
        return action == SignalTrainLifecycleAction.ADD
            ? SignalTrainLifecycleCommand.add(trains)
            : SignalTrainLifecycleCommand.delete(trains);
    }

    private SignalTrainLifecycleTrainSpec toTrainSpec(
        SignalTrainLifecycleAction action,
        TrainLifecycleCommandRequest.TrainLifecycleTrainRequest train
    ) {
        int trainNo = requiredTrainNo(train);
        if (action == SignalTrainLifecycleAction.DELETE) {
            return SignalTrainLifecycleTrainSpec.delete(trainNo);
        }
        return SignalTrainLifecycleTrainSpec.add(
            trainNo,
            train.linkId() == null ? 0 : train.linkId(),
            train.offsetMeters() == null ? 0 : train.offsetMeters(),
            parseDirection(train.direction())
        );
    }

    private int requiredTrainNo(TrainLifecycleCommandRequest.TrainLifecycleTrainRequest train) {
        if (train == null || train.trainNo() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "trainNo is required");
        }
        if (train.trainNo() < 1 || train.trainNo() > 65_535) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "trainNo must be in 1..65535");
        }
        return train.trainNo();
    }

    private SignalTrainLifecycleAction parseAction(String action) {
        if (action == null || action.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action is required");
        }
        try {
            return SignalTrainLifecycleAction.valueOf(action.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action must be ADD, DELETE, or CLEAR");
        }
    }

    private ExternalTrainDirection parseDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return ExternalTrainDirection.UNKNOWN;
        }
        try {
            return ExternalTrainDirection.valueOf(direction.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "direction must be UP, DOWN, or UNKNOWN");
        }
    }
}
