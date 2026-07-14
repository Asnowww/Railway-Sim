package com.railwaysim.vehicle.telemetry;

import com.railwaysim.simulation.SimulationRunContext;
import com.railwaysim.vehicle.protocol.TrainOperationalTelemetry;
import com.railwaysim.vehicle.runtime.VehicleRuntimeIntegrationService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 8080 protocol gateway: validates/normalizes ingress and forwards it without mutating TrainEntity. */
@Service
public class VehicleTelemetryGatewayService {

    private static final Logger log = LoggerFactory.getLogger(VehicleTelemetryGatewayService.class);
    private final VehicleRuntimeIntegrationService runtimeIntegrationService;
    private final SimulationRunContext runContext;
    private final ConcurrentMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    public VehicleTelemetryGatewayService(
        VehicleRuntimeIntegrationService runtimeIntegrationService,
        SimulationRunContext runContext
    ) {
        this.runtimeIntegrationService = runtimeIntegrationService;
        this.runContext = runContext;
    }

    public VehicleTelemetryResponse forward(
        String sourceId,
        List<TrainOperationalTelemetry> telemetries
    ) {
        String normalizedSourceId = sourceId == null || sourceId.isBlank() ? "SIGNAL_UNKNOWN" : sourceId;
        long sequence = sequences.computeIfAbsent(normalizedSourceId, ignored -> new AtomicLong()).incrementAndGet();
        Instant sourceTimestamp = Instant.now();
        VehicleTelemetryRequest request = new VehicleTelemetryRequest(
            runContext.runId(), normalizedSourceId, sourceTimestamp, sequence,
            (telemetries == null ? List.<TrainOperationalTelemetry>of() : telemetries).stream()
                .map(this::normalize)
                .toList()
        );
        VehicleTelemetryResponse response = runtimeIntegrationService.forwardTelemetry(request);
        log.info(
            "Vehicle telemetry protocol audit source={} run={} sequence={} trains={} accepted={}",
            normalizedSourceId, request.simulationRunId(), sequence, request.telemetries().size(), response.accepted()
        );
        return response;
    }

    private VehicleTelemetrySample normalize(TrainOperationalTelemetry telemetry) {
        return new VehicleTelemetrySample(
            telemetry.trainId(), telemetry.speedMetersPerSecond(), telemetry.cumulativeDistanceMeters(),
            telemetry.direction().name(), telemetry.loadMassKg(), telemetry.faultSpeedLimitMetersPerSecond(),
            telemetry.emergencyBrakeApplied(), telemetry.availableTractionCount(), telemetry.availableBrakeCount()
        );
    }
}
