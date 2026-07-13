package com.railwaysim.vehicle.external;

import com.railwaysim.vehicle.VehiclePhysicsInput;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.util.List;

/**
 * 本地回退车辆适配器存根。
 *
 * <p>LOCAL 模式已移除，此适配器仅在 EXTERNAL_UDP / EXTERNAL_RTLAB_API / DUAL_SHADOW
 * 三种外部仿真链路中作为解析层回调存在。当外部仿真器不可用时，stepFleet 抛出异常；
 * markFault 在原输出上标记故障码。
 */
public class LocalFallbackVehicleAdapter {

    public List<VehiclePhysicsOutput> stepFleet(List<VehiclePhysicsInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        throw new IllegalStateException("Local fallback vehicle adapter is no longer available (LOCAL mode removed)");
    }

    public List<VehiclePhysicsOutput> markFault(List<VehiclePhysicsOutput> outputs, String faultCode) {
        if (outputs == null) return List.of();
        return outputs.stream()
            .map(output -> new VehiclePhysicsOutput(
                output.trainId(),
                output.newPositionMeters(),
                output.newSpeedMetersPerSecond(),
                output.accelerationMetersPerSecondSquared(),
                output.tractionForceNewtons(),
                output.brakeForceNewtons(),
                output.regenBrakeForceNewtons(),
                output.tractionPowerWatts(),
                output.railCurrentAmps(),
                output.regenPowerWatts(),
                output.energyConsumedKwh(),
                output.energyRegeneratedKwh(),
                faultCode
            ))
            .toList();
    }
}
