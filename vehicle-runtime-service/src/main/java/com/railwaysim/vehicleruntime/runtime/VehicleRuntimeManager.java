package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.model.MovementAuthoritySnapshot;
import com.railwaysim.vehicleruntime.model.PowerConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.TrackConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.TrainStateReportDto;
import com.railwaysim.vehicleruntime.model.TrainStateSnapshot;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsOutputDto;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeBootstrapRequest;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeEvent;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeHealth;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeInstanceState;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeStepRequest;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeStepResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 外部车辆运行时总入口，集中管理每车实例和同步 step-fleet 调度。
 */
@Service
public class VehicleRuntimeManager {

    private final VehicleRuntimeProperties properties;
    private final Map<String, VehicleRuntimeInstance> instances = new ConcurrentHashMap<>();
    private final List<VehicleRuntimeEvent> events = new ArrayList<>();
    private volatile VehicleRuntimeHealth latestHealth = new VehicleRuntimeHealth("EXTERNAL_HTTP", "UP", Instant.now(), 0, "GOOD", 0, "READY");

    public VehicleRuntimeManager(VehicleRuntimeProperties properties) {
        this.properties = properties;
    }

    public VehicleRuntimeHealth health() {
        return new VehicleRuntimeHealth(
            "EXTERNAL_HTTP",
            latestHealth.heartbeatStatus(),
            Instant.now(),
            latestHealth.latencyMillis(),
            latestHealth.dataQuality(),
            instances.size(),
            latestHealth.reason()
        );
    }

    public synchronized VehicleRuntimeHealth bootstrap(VehicleRuntimeBootstrapRequest request) {
        if (request != null) {
            properties.setDefaultLineLengthMeters(request.defaultLineLengthMeters());
            properties.setDefaultSpeedLimitMetersPerSecond(request.defaultSpeedLimitMetersPerSecond());
            properties.setSafetyGapMeters(request.safetyGapMeters());
        }
        recordEvent("runtime", "BOOTSTRAP", "external vehicle runtime bootstrapped");
        latestHealth = new VehicleRuntimeHealth("EXTERNAL_HTTP", "UP", Instant.now(), 0, "GOOD", instances.size(), "BOOTSTRAPPED");
        return health();
    }

    public VehicleRuntimeInstanceState register(TrainStateSnapshot train) {
        String trainId = train == null ? null : train.id();
        if (trainId == null || trainId.isBlank()) {
            throw new IllegalArgumentException("trainId is required");
        }
        VehicleRuntimeInstance instance = instances.computeIfAbsent(trainId, id -> new VehicleRuntimeInstance(id, properties));
        recordEvent(trainId, "REGISTER", "vehicle runtime instance registered");
        return instance.state();
    }

    public void remove(String trainId) {
        if (trainId != null && instances.remove(trainId) != null) {
            recordEvent(trainId, "REMOVE", "vehicle runtime instance removed");
        }
    }

    public void clear() {
        instances.clear();
        recordEvent("runtime", "CLEAR", "all vehicle runtime instances cleared");
    }

    public List<VehicleRuntimeInstanceState> instances() {
        return instances.values().stream()
            .map(VehicleRuntimeInstance::state)
            .sorted(Comparator.comparing(VehicleRuntimeInstanceState::trainId))
            .toList();
    }

    public synchronized VehicleRuntimeStepResponse stepFleet(VehicleRuntimeStepRequest request) {
        Instant startedAt = Instant.now();
        List<TrainStateSnapshot> trains = request.trains() == null ? List.of() : request.trains();
        Map<String, MovementAuthoritySnapshot> authorityByTrain = index(request.movementAuthorities(), MovementAuthoritySnapshot::trainId);
        Map<String, TrackConstraintSnapshot> trackByTrain = index(request.trackConstraints(), TrackConstraintSnapshot::trainId);
        Map<String, PowerConstraintSnapshot> powerByTrain = index(request.powerConstraints(), PowerConstraintSnapshot::trainId);
        List<VehiclePhysicsOutputDto> outputs = new ArrayList<>();
        List<TrainStateReportDto> reports = new ArrayList<>();
        List<VehicleRuntimeInstanceState> states = new ArrayList<>();

        for (TrainStateSnapshot train : trains) {
            VehicleRuntimeInstance instance = instances.computeIfAbsent(train.id(), id -> new VehicleRuntimeInstance(id, properties));
            VehicleRuntimeInstance.StepResult result = instance.step(
                request.tick(),
                request.deltaSeconds(),
                train,
                authorityByTrain.get(train.id()),
                trackByTrain.get(train.id()),
                powerByTrain.get(train.id())
            );
            result.output().ifPresent(outputs::add);
            result.report().ifPresent(reports::add);
            states.add(result.state());
        }

        String dataQuality = outputs.size() == trains.size() && reports.size() == trains.size() ? "GOOD" : "DEGRADED";
        long latency = Duration.between(startedAt, Instant.now()).toMillis();
        latestHealth = new VehicleRuntimeHealth(
            "EXTERNAL_HTTP",
            "UP",
            Instant.now(),
            latency,
            dataQuality,
            instances.size(),
            dataQuality.equals("GOOD") ? "OK" : "PARTIAL_STEP"
        );
        return new VehicleRuntimeStepResponse(request.tick(), Instant.now(), dataQuality, outputs, reports, states);
    }

    public synchronized List<VehicleRuntimeEvent> events() {
        return List.copyOf(events);
    }

    private <T> Map<String, T> index(List<T> values, Function<T, String> keyFn) {
        return (values == null ? List.<T>of() : values).stream()
            .filter(value -> keyFn.apply(value) != null)
            .collect(Collectors.toMap(keyFn, Function.identity(), (left, right) -> right));
    }

    private synchronized void recordEvent(String trainId, String type, String detail) {
        events.add(new VehicleRuntimeEvent(type + "-" + Instant.now().toEpochMilli(), trainId, type, detail, Instant.now()));
    }
}
