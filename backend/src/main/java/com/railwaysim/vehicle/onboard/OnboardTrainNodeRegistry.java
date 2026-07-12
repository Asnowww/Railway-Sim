package com.railwaysim.vehicle.onboard;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** @deprecated LOCAL mode only. */
@Deprecated(forRemoval=true, since="2.0")
class OnboardTrainNodeRegistry {

    private final Map<String, NodeRecord> nodes = new ConcurrentHashMap<>();

    OnboardTrainNodeState register(
        OnboardTrainRegistration registration,
        String requestedMode,
        long leaseMillis
    ) {
        Instant now = Instant.now();
        NodeRecord record = nodes.computeIfAbsent(registration.trainId(), NodeRecord::new);
        record.subsystemId = registration.subsystemId();
        record.requestedMode = requestedMode;
        record.activeMode = registration.mode();
        record.connectionState = "REGISTERED";
        record.dataQuality = "GOOD";
        record.registeredAt = record.registeredAt == null ? now : record.registeredAt;
        record.lastHeartbeatAt = now;
        record.leaseExpiresAt = now.plusMillis(leaseMillis);
        record.lastError = null;
        return record.snapshot();
    }

    OnboardTrainNodeState markOnline(
        String trainId,
        String subsystemId,
        String requestedMode,
        String activeMode,
        long leaseMillis
    ) {
        Instant now = Instant.now();
        NodeRecord record = nodes.computeIfAbsent(trainId, NodeRecord::new);
        record.subsystemId = subsystemId;
        record.requestedMode = requestedMode;
        record.activeMode = activeMode;
        record.connectionState = "ONLINE";
        record.dataQuality = "GOOD";
        record.registeredAt = record.registeredAt == null ? now : record.registeredAt;
        record.lastHeartbeatAt = now;
        record.lastControlAt = now;
        record.leaseExpiresAt = now.plusMillis(leaseMillis);
        record.lastError = null;
        return record.snapshot();
    }

    OnboardTrainNodeState markFallback(
        String trainId,
        String requestedMode,
        OnboardTrainRegistration fallbackRegistration,
        String error,
        long leaseMillis
    ) {
        Instant now = Instant.now();
        NodeRecord record = nodes.computeIfAbsent(trainId, NodeRecord::new);
        record.subsystemId = fallbackRegistration == null ? "ONBOARD-" + trainId : fallbackRegistration.subsystemId();
        record.requestedMode = requestedMode;
        record.activeMode = fallbackRegistration == null ? "IN_PROCESS_SIMULATED" : fallbackRegistration.mode();
        record.connectionState = "FALLBACK";
        record.dataQuality = "FALLBACK";
        record.registeredAt = record.registeredAt == null ? now : record.registeredAt;
        record.lastHeartbeatAt = now;
        record.lastControlAt = now;
        record.leaseExpiresAt = now.plusMillis(leaseMillis);
        record.lastError = error;
        return record.snapshot();
    }

    void remove(String trainId) {
        nodes.remove(trainId);
    }

    void clear() {
        nodes.clear();
    }

    int count() {
        return nodes.size();
    }

    List<OnboardTrainNodeState> states() {
        return nodes.values().stream()
            .map(NodeRecord::snapshot)
            .sorted(Comparator.comparing(OnboardTrainNodeState::trainId))
            .toList();
    }

    private static final class NodeRecord {
        private final String trainId;
        private String subsystemId;
        private String requestedMode;
        private String activeMode;
        private String connectionState;
        private String dataQuality;
        private Instant registeredAt;
        private Instant lastHeartbeatAt;
        private Instant lastControlAt;
        private Instant leaseExpiresAt;
        private String lastError;

        private NodeRecord(String trainId) {
            this.trainId = trainId;
            this.subsystemId = "ONBOARD-" + trainId;
            this.requestedMode = OnboardTrainSubsystemMode.IN_PROCESS.name();
            this.activeMode = "IN_PROCESS_SIMULATED";
            this.connectionState = "REGISTERED";
            this.dataQuality = "GOOD";
        }

        private OnboardTrainNodeState snapshot() {
            return new OnboardTrainNodeState(
                trainId,
                subsystemId,
                requestedMode,
                activeMode,
                connectionState,
                dataQuality,
                registeredAt,
                lastHeartbeatAt,
                lastControlAt,
                leaseExpiresAt,
                lastError
            );
        }
    }
}
