package com.railwaysim.vehicle.external;

import com.railwaysim.config.ExternalSimulatorProperties;
import com.railwaysim.vehicle.VehiclePhysicsInput;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShadowCompareAdapter implements ExternalVehicleSimulationAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ShadowCompareAdapter.class);

    private final LocalFallbackVehicleAdapter localFallback;
    private final ExternalVehicleSimulationAdapter shadowAdapter;
    private final ExternalSimulatorProperties.ShadowProperties shadowProperties;
    private volatile ExternalSimulatorHealth health = ExternalSimulatorHealth.fallback(
        ExternalSimulatorMode.DUAL_SHADOW,
        "NOT_STARTED"
    );

    public ShadowCompareAdapter(
        LocalFallbackVehicleAdapter localFallback,
        ExternalVehicleSimulationAdapter shadowAdapter,
        ExternalSimulatorProperties.ShadowProperties shadowProperties
    ) {
        this.localFallback = localFallback;
        this.shadowAdapter = shadowAdapter;
        this.shadowProperties = shadowProperties;
    }

    @Override
    public List<VehiclePhysicsOutput> stepFleet(List<VehiclePhysicsInput> inputs) {
        List<VehiclePhysicsOutput> localOutputs = localFallback.stepFleet(inputs);
        try {
            List<VehiclePhysicsOutput> shadowOutputs = shadowAdapter.stepFleet(inputs);
            compare(localOutputs, shadowOutputs);
            health = new ExternalSimulatorHealth(
                ExternalSimulatorMode.DUAL_SHADOW,
                shadowAdapter.health().heartbeatStatus(),
                shadowAdapter.health().lastPacketAt(),
                shadowAdapter.health().latencyMillis(),
                shadowAdapter.health().dataQuality()
            );
            return localOutputs;
        } catch (RuntimeException exception) {
            health = ExternalSimulatorHealth.fallback(ExternalSimulatorMode.DUAL_SHADOW, exception.getClass().getSimpleName());
            return localFallback.markFault(localOutputs, "EXTERNAL_SIM_FALLBACK");
        }
    }

    @Override
    public ExternalSimulatorHealth health() {
        return health;
    }

    private void compare(List<VehiclePhysicsOutput> localOutputs, List<VehiclePhysicsOutput> shadowOutputs) {
        Map<String, VehiclePhysicsOutput> shadowByTrain = shadowOutputs.stream()
            .collect(Collectors.toMap(VehiclePhysicsOutput::trainId, Function.identity(), (left, right) -> right));
        localOutputs.forEach(local -> {
            VehiclePhysicsOutput shadow = shadowByTrain.get(local.trainId());
            if (shadow == null) {
                logger.warn("External shadow output missing for train {}", local.trainId());
                return;
            }
            double speedDelta = Math.abs(local.newSpeedMetersPerSecond() - shadow.newSpeedMetersPerSecond());
            double accelerationDelta = Math.abs(
                local.accelerationMetersPerSecondSquared() - shadow.accelerationMetersPerSecondSquared()
            );
            double distanceDelta = Math.abs(local.newPositionMeters() - shadow.newPositionMeters());
            if (
                speedDelta > shadowProperties.getSpeedToleranceMetersPerSecond()
                    || accelerationDelta > shadowProperties.getAccelerationToleranceMetersPerSecondSquared()
                    || distanceDelta > shadowProperties.getDistanceToleranceMeters()
            ) {
                logger.warn(
                    "External shadow mismatch train={} speedDelta={} accelerationDelta={} distanceDelta={}",
                    local.trainId(),
                    speedDelta,
                    accelerationDelta,
                    distanceDelta
                );
            }
        });
    }
}
