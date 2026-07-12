package com.railwaysim.vehicle.control;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 共享的司机台命令持有者，避免 TrainManager 与 VehicleRuntimeIntegrationService 循环依赖。
 */
@Component
public class DriverCommandHolder {

    private final Map<String, DriverControlCommand> commands = new ConcurrentHashMap<>();

    public void store(String trainId, DriverControlCommand cmd) {
        if (cmd != null) {
            commands.put(trainId, cmd);
        }
    }

    public DriverControlCommand latest(String trainId) {
        return commands.get(trainId);
    }

    public void clear() {
        commands.clear();
    }
}
