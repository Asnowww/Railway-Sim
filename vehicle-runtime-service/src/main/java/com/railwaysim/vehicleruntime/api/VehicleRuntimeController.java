package com.railwaysim.vehicleruntime.api;

import com.railwaysim.vehicleruntime.model.DriverCommandAcceptance;
import com.railwaysim.vehicleruntime.model.DriverControlCommandSnapshot;
import com.railwaysim.vehicleruntime.model.ManualControlRequest;
import com.railwaysim.vehicleruntime.model.TrainStateSnapshot;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeBootstrapRequest;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeEvent;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeHealth;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeInstanceState;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeLaunchRequest;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeLaunchResponse;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeStepRequest;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeStepResponse;
import com.railwaysim.vehicleruntime.model.VehicleParameterMetadata;
import com.railwaysim.vehicleruntime.model.VehicleTelemetryModeRequest;
import com.railwaysim.vehicleruntime.model.VehicleTelemetryModeState;
import com.railwaysim.vehicleruntime.model.VehicleTelemetryRequest;
import com.railwaysim.vehicleruntime.model.VehicleTelemetryResponse;
import com.railwaysim.vehicleruntime.runtime.DriverCommandHolder;
import com.railwaysim.vehicleruntime.runtime.VehicleRuntimeManager;
import com.railwaysim.vehicleruntime.runtime.VehicleRuntimeTickClock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 外部车辆运行时 HTTP 边界，前端不直接访问，中央只通过受控接口同步状态。
 */
@RestController
@RequestMapping("/vehicle-runtime")
public class VehicleRuntimeController {

    private final VehicleRuntimeManager manager;
    private final DriverCommandHolder commandHolder;
    private final VehicleRuntimeTickClock tickClock;
    private final ConcurrentMap<String, Integer> sequenceNumbers = new ConcurrentHashMap<>();

    public VehicleRuntimeController(
        VehicleRuntimeManager manager,
        DriverCommandHolder commandHolder,
        VehicleRuntimeTickClock tickClock
    ) {
        this.manager = manager;
        this.commandHolder = commandHolder;
        this.tickClock = tickClock;
    }

    @GetMapping("/health")
    public VehicleRuntimeHealth health() {
        return manager.health();
    }

    @GetMapping("/parameters")
    public VehicleParameterMetadata parameters() {
        return manager.parameterMetadata();
    }

    @PostMapping("/bootstrap")
    public VehicleRuntimeHealth bootstrap(@RequestBody(required = false) VehicleRuntimeBootstrapRequest request) {
        return manager.bootstrap(request);
    }

    @PutMapping("/trains/{trainId}")
    public VehicleRuntimeInstanceState register(@PathVariable String trainId, @RequestBody(required = false) TrainStateSnapshot train) {
        if (train != null && train.id() != null && !train.id().isBlank() && !trainId.equals(train.id())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path trainId and body id must match");
        }
        TrainStateSnapshot effective = train == null || train.id() == null || train.id().isBlank() ? minimalTrain(trainId) : train;
        return manager.register(effective);
    }

    @PostMapping("/trains/launch")
    public VehicleRuntimeLaunchResponse launch(@RequestBody VehicleRuntimeLaunchRequest request) {
        return manager.launch(request);
    }

    @DeleteMapping("/trains/{trainId}")
    public void remove(@PathVariable String trainId) {
        manager.remove(trainId);
    }

    @DeleteMapping("/trains")
    public void clear() {
        manager.clear();
    }

    @GetMapping("/instances")
    public List<VehicleRuntimeInstanceState> instances() {
        return manager.instances();
    }

    @PostMapping("/step-fleet")
    public VehicleRuntimeStepResponse stepFleet(@RequestBody VehicleRuntimeStepRequest request) {
        return manager.stepFleet(request);
    }

    @PostMapping("/telemetry")
    public VehicleTelemetryResponse acceptTelemetry(@RequestBody VehicleTelemetryRequest request) {
        return manager.acceptTelemetry(request);
    }

    @PutMapping("/trains/{trainId}/telemetry-mode")
    public VehicleTelemetryModeState configureTelemetryMode(
        @PathVariable String trainId,
        @RequestBody VehicleTelemetryModeRequest request
    ) {
        return manager.configureTelemetryMode(trainId, request);
    }

    @GetMapping("/trains/{trainId}/telemetry-mode")
    public VehicleTelemetryModeState telemetryMode(@PathVariable String trainId) {
        return manager.telemetryMode(trainId);
    }

    @PostMapping("/physics/instances/{trainId}/reset")
    public void resetPhysics(@PathVariable String trainId) {
        manager.resetPhysics(trainId);
    }

    @PostMapping("/physics/instances/{trainId}/resync")
    public void resyncPhysics(@PathVariable String trainId) {
        manager.resyncPhysics(trainId);
    }

    @PostMapping("/physics/instances/resync-all")
    public void resyncAllPhysics() {
        manager.resyncAllPhysics();
    }

    @GetMapping("/events")
    public List<VehicleRuntimeEvent> events() {
        return manager.events();
    }

    // ========== 手动控制 ==========

    @PostMapping("/trains/{trainId}/manual-control")
    public DriverCommandAcceptance manualControl(
        @PathVariable String trainId,
        @RequestBody ManualControlRequest request
    ) {
        if (!manager.hasInstance(trainId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Instance not found: " + trainId);
        }
        int seqNo = sequenceNumbers.merge(trainId, 1, (old, v) -> old + 1);
        Instant now = Instant.now();
        long timeout = request.timeoutMs() != null ? request.timeoutMs() : 5000;

        DriverControlCommandSnapshot cmd = new DriverControlCommandSnapshot(
            "MANUAL-" + trainId + "-" + seqNo,
            trainId, seqNo, now, now.plusMillis(timeout),
            clamp(request.tractionCommand(), 0, 1),
            clamp(request.brakeCommand(), 0, 1),
            request.emergencyBrake(),
            request.direction(),
            request.doorOpenRequest() != null && request.doorOpenRequest(),
            false,
            "MANUAL",
            "manual-trace-" + trainId + "-" + seqNo
        );
        commandHolder.store(cmd);
        return DriverCommandAcceptance.accepted(cmd);
    }

    // ========== 状态查询 ==========

    @GetMapping("/trains/{trainId}/state")
    public TrainStateSnapshot getTrainState(@PathVariable String trainId) {
        return manager.getTrainState(trainId);
    }

    @GetMapping("/trains/state")
    public List<TrainStateSnapshot> getAllTrainStates() {
        return manager.getAllTrainStates();
    }

    // ========== 自主模式 ==========

    @PostMapping("/autonomous/enable")
    public void enableAutonomous() {
        tickClock.enable();
    }

    @PostMapping("/autonomous/disable")
    public void disableAutonomous() {
        tickClock.disable();
    }

    @PostMapping("/tick")
    public VehicleRuntimeStepResponse tick() {
        tickClock.autonomousTick();
        // Return current states after tick
        return new VehicleRuntimeStepResponse(
            tickClock.getCurrentTick(), Instant.now(), "GOOD",
            List.of(), List.of(), manager.instances(),
            manager.getAllTrainStates(), List.of());
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private TrainStateSnapshot minimalTrain(String trainId) {
        return new TrainStateSnapshot(
            trainId,
            "",
            trainId,
            "IN_SERVICE",
            "ATTACHED",
            "ATTACHED",
            "EXTERNAL_RUNTIME_REGISTERED",
            0,
            "UNKNOWN",
            0,
            0,
            manager.parameterMetadata().lengthMeters(),
            0,
            0,
            0,
            0,
            "NORMAL",
            4,
            4,
            "NONE",
            "RUNNING",
            "ATO",
            true,
            "CLOSED_LOCKED",
            "IDLE",
            "RELEASED",
            "NORMAL",
            true,
            true,
            "PASS",
            0,
            "NORMAL",
            "GOOD",
            "COASTING",
            "INITIAL",
            22.2,
            0,
            0,
            1_000_000,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            "OK"
        );
    }
}
