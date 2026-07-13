package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.model.VehicleTelemetryMode;
import com.railwaysim.vehicleruntime.model.VehicleTelemetryModeRequest;
import com.railwaysim.vehicleruntime.model.VehicleTelemetryModeState;
import com.railwaysim.vehicleruntime.model.VehicleTelemetryRequest;
import com.railwaysim.vehicleruntime.model.VehicleTelemetryResponse;
import com.railwaysim.vehicleruntime.model.VehicleTelemetryResult;
import com.railwaysim.vehicleruntime.model.VehicleTelemetrySample;
import com.railwaysim.vehicleruntime.model.VehicleTelemetryStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Queues external telemetry and applies authoritative fields only at a fleet tick boundary. */
@Service
public class VehicleTelemetryCoordinator {

    private final VehicleRuntimeProperties properties;
    private final Map<String, Long> lastSequenceBySource = new HashMap<>();
    private final Map<String, Double> lastMileageBySourceAndTrain = new HashMap<>();
    private final Map<String, TrainTelemetryState> states = new HashMap<>();
    private long effectiveFromTick;

    public VehicleTelemetryCoordinator(VehicleRuntimeProperties properties) {
        this.properties = properties;
    }

    public synchronized VehicleTelemetryResponse accept(
        VehicleTelemetryRequest request,
        String activeRunId,
        Set<String> knownTrainIds,
        Instant receivedAt
    ) {
        if (request == null) {
            return response(List.of(result(null, VehicleTelemetryStatus.INVALID_VALUE, null, "REQUEST_REQUIRED")));
        }
        List<VehicleTelemetrySample> telemetries = request.telemetries();
        if (request.simulationRunId() == null || request.simulationRunId().isBlank()
            || activeRunId == null || activeRunId.isBlank()
            || !request.simulationRunId().equals(activeRunId)) {
            return responseForAll(telemetries, VehicleTelemetryStatus.RUN_ID_MISMATCH, "ACTIVE_RUN_MISMATCH");
        }
        if (request.sourceId() == null || request.sourceId().isBlank()
            || request.sourceTimestamp() == null || request.sequence() < 0) {
            return responseForAll(telemetries, VehicleTelemetryStatus.INVALID_VALUE, "INVALID_ENVELOPE");
        }
        long maximumAgeMillis = properties.getTelemetryMaximumAgeMillis();
        long ageMillis = Duration.between(request.sourceTimestamp(), receivedAt).toMillis();
        if (ageMillis > maximumAgeMillis) {
            return responseForAll(telemetries, VehicleTelemetryStatus.STALE, "SOURCE_TIMESTAMP_EXPIRED");
        }
        if (ageMillis < -maximumAgeMillis) {
            return responseForAll(telemetries, VehicleTelemetryStatus.INVALID_VALUE, "SOURCE_TIMESTAMP_IN_FUTURE");
        }
        Long previousSequence = lastSequenceBySource.get(request.sourceId());
        if (previousSequence != null && request.sequence() <= previousSequence) {
            return responseForAll(telemetries, VehicleTelemetryStatus.OUT_OF_ORDER, "SEQUENCE_NOT_INCREASING");
        }
        lastSequenceBySource.put(request.sourceId(), request.sequence());

        List<VehicleTelemetryResult> results = new ArrayList<>();
        for (VehicleTelemetrySample telemetry : telemetries) {
            String trainId = telemetry == null ? null : telemetry.trainId();
            if (trainId == null || trainId.isBlank() || !knownTrainIds.contains(trainId)) {
                results.add(result(trainId, VehicleTelemetryStatus.UNKNOWN_TRAIN, null, "TRAIN_NOT_REGISTERED"));
                continue;
            }
            TrainTelemetryState state = states.computeIfAbsent(trainId, ignored -> new TrainTelemetryState());
            if (state.mode == VehicleTelemetryMode.DISABLED) {
                results.add(result(trainId, VehicleTelemetryStatus.MODE_NOT_ALLOWED, null, "TELEMETRY_DISABLED"));
                continue;
            }
            String invalidReason = invalidReason(telemetry);
            if (invalidReason != null) {
                results.add(result(trainId, VehicleTelemetryStatus.INVALID_VALUE, null, invalidReason));
                continue;
            }
            String mileageKey = request.sourceId() + '\u0000' + trainId;
            Double previousMileage = lastMileageBySourceAndTrain.get(mileageKey);
            if (previousMileage != null && telemetry.cumulativeDistanceMeters() + 1.0e-6 < previousMileage) {
                results.add(result(trainId, VehicleTelemetryStatus.OUT_OF_ORDER, null, "MILEAGE_REGRESSION"));
                continue;
            }
            lastMileageBySourceAndTrain.put(mileageKey, telemetry.cumulativeDistanceMeters());
            state.lastObservedAt = receivedAt;
            if (state.mode == VehicleTelemetryMode.AUTHORITATIVE) {
                state.pending = new PendingTelemetry(request.simulationRunId(), telemetry, receivedAt);
                if (telemetry.emergencyBrakeApplied()) {
                    state.emergencyBrakeLatched = true;
                }
            }
            results.add(result(trainId, VehicleTelemetryStatus.ACCEPTED, effectiveFromTick, state.mode.name()));
        }
        return response(results);
    }

    public synchronized VehicleTelemetryModeState configureMode(
        String trainId,
        VehicleTelemetryModeRequest request,
        Set<String> knownTrainIds
    ) {
        if (!knownTrainIds.contains(trainId)) {
            throw new IllegalArgumentException("unknown train instance: " + trainId);
        }
        if (request == null || request.mode() == null) {
            throw new IllegalArgumentException("telemetry mode is required");
        }
        TrainTelemetryState state = states.computeIfAbsent(trainId, ignored -> new TrainTelemetryState());
        state.mode = request.mode();
        if (request.recoverFromHold()) {
            state.holdLatched = false;
        }
        if (request.clearEmergencyBrakeLatch()) {
            state.emergencyBrakeLatched = false;
        }
        if (request.mode() != VehicleTelemetryMode.AUTHORITATIVE) {
            state.pending = null;
        }
        return snapshot(trainId, state);
    }

    public synchronized VehicleTelemetryModeState mode(String trainId) {
        return snapshot(trainId, states.computeIfAbsent(trainId, ignored -> new TrainTelemetryState()));
    }

    synchronized void applyAtTick(
        String activeRunId,
        long tick,
        List<String> activeTrainIds,
        Map<String, VehicleRuntimeInstance> instances,
        Instant tickStartedAt
    ) {
        for (String trainId : activeTrainIds) {
            TrainTelemetryState state = states.computeIfAbsent(trainId, ignored -> new TrainTelemetryState());
            if (state.mode != VehicleTelemetryMode.AUTHORITATIVE) {
                continue;
            }
            VehicleRuntimeInstance instance = instances.get(trainId);
            if (instance == null) {
                continue;
            }
            if (state.holdLatched) {
                instance.applyTelemetryHold();
                continue;
            }
            PendingTelemetry pending = state.pending;
            if (pending != null && activeRunId.equals(pending.runId())) {
                if (expired(pending.receivedAt(), tickStartedAt)) {
                    state.holdLatched = true;
                    instance.applyTelemetryHold();
                } else {
                    instance.applyAuthoritativeTelemetry(pending.telemetry(), state.emergencyBrakeLatched);
                    state.lastAppliedAt = pending.receivedAt();
                    state.pending = null;
                }
                continue;
            }
            if (state.lastAppliedAt == null || expired(state.lastAppliedAt, tickStartedAt)) {
                state.holdLatched = true;
                instance.applyTelemetryHold();
            }
        }
        effectiveFromTick = tick + 1;
    }

    public synchronized void rollover() {
        lastSequenceBySource.clear();
        lastMileageBySourceAndTrain.clear();
        effectiveFromTick = 0;
        states.values().forEach(state -> {
            state.pending = null;
            state.lastAppliedAt = null;
            state.lastObservedAt = null;
            state.holdLatched = false;
            state.emergencyBrakeLatched = false;
        });
    }

    public synchronized void remove(String trainId) {
        states.remove(trainId);
        lastMileageBySourceAndTrain.keySet().removeIf(key -> key.endsWith("\u0000" + trainId));
    }

    public synchronized void clear() {
        lastSequenceBySource.clear();
        lastMileageBySourceAndTrain.clear();
        states.clear();
    }

    private boolean expired(Instant receivedAt, Instant now) {
        return Duration.between(receivedAt, now).toMillis() > properties.getTelemetryTimeoutMillis();
    }

    private String invalidReason(VehicleTelemetrySample telemetry) {
        if (!finiteNonNegative(telemetry.speedMetersPerSecond())
            || !finiteNonNegative(telemetry.cumulativeDistanceMeters())
            || !finiteNonNegative(telemetry.loadMassKg())
            || !finiteNonNegative(telemetry.faultSpeedLimitMetersPerSecond())) {
            return "NON_FINITE_OR_NEGATIVE_VALUE";
        }
        if (telemetry.availableTractionCount() < 0 || telemetry.availableBrakeCount() < 0) {
            return "NEGATIVE_UNIT_COUNT";
        }
        if (!("UP".equalsIgnoreCase(telemetry.direction()) || "DOWN".equalsIgnoreCase(telemetry.direction()))) {
            return "INVALID_DIRECTION";
        }
        return null;
    }

    private boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0;
    }

    private VehicleTelemetryResponse responseForAll(
        List<VehicleTelemetrySample> telemetries,
        VehicleTelemetryStatus status,
        String reason
    ) {
        List<VehicleTelemetryResult> results = telemetries.isEmpty()
            ? List.of(result(null, status, null, reason))
            : telemetries.stream()
                .map(value -> result(value == null ? null : value.trainId(), status, null, reason))
                .toList();
        return response(results);
    }

    private VehicleTelemetryResponse response(List<VehicleTelemetryResult> results) {
        boolean accepted = !results.isEmpty()
            && results.stream().allMatch(result -> result.status() == VehicleTelemetryStatus.ACCEPTED);
        return new VehicleTelemetryResponse(accepted, results);
    }

    private VehicleTelemetryResult result(
        String trainId,
        VehicleTelemetryStatus status,
        Long effectiveFromTick,
        String reason
    ) {
        return new VehicleTelemetryResult(trainId, status, effectiveFromTick, reason);
    }

    private VehicleTelemetryModeState snapshot(String trainId, TrainTelemetryState state) {
        return new VehicleTelemetryModeState(
            trainId, state.mode, state.holdLatched, state.emergencyBrakeLatched
        );
    }

    private static final class TrainTelemetryState {
        private VehicleTelemetryMode mode = VehicleTelemetryMode.OBSERVE_ONLY;
        private PendingTelemetry pending;
        private Instant lastObservedAt;
        private Instant lastAppliedAt;
        private boolean holdLatched;
        private boolean emergencyBrakeLatched;
    }

    private record PendingTelemetry(
        String runId,
        VehicleTelemetrySample telemetry,
        Instant receivedAt
    ) {
    }
}
