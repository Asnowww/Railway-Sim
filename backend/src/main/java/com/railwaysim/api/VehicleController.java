package com.railwaysim.api;

import com.railwaysim.api.dto.VehicleMaintenanceStateResponse;
import com.railwaysim.train.TrainManager;
import com.railwaysim.vehicle.onboard.OnboardTrainNodeState;
import com.railwaysim.vehicle.onboard.OnboardTrainSubsystemManager;
import com.railwaysim.vehicle.runtime.VehicleRuntimeIntegrationService;
import com.railwaysim.vehicle.runtime.VehicleRuntimeStatusResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vehicle")
@CrossOrigin
public class VehicleController {

    private final TrainManager trainManager;
    private final OnboardTrainSubsystemManager onboardTrainSubsystemManager;
    private final VehicleRuntimeIntegrationService vehicleRuntimeIntegrationService;

    public VehicleController(
        TrainManager trainManager,
        OnboardTrainSubsystemManager onboardTrainSubsystemManager,
        VehicleRuntimeIntegrationService vehicleRuntimeIntegrationService
    ) {
        this.trainManager = trainManager;
        this.onboardTrainSubsystemManager = onboardTrainSubsystemManager;
        this.vehicleRuntimeIntegrationService = vehicleRuntimeIntegrationService;
    }

    @GetMapping("/maintenance-states")
    public List<VehicleMaintenanceStateResponse> maintenanceStates() {
        return trainManager.states().stream()
            .map(train -> new VehicleMaintenanceStateResponse(
                train.id(),
                train.serviceNo(),
                maintenanceState(train.faultLevel()),
                train.faultCode(),
                train.faultLevel(),
                train.selfCheckStatus(),
                train.availableOperationMode(),
                train.dataQuality(),
                Instant.now()
            ))
            .toList();
    }

    @GetMapping("/onboard-subsystems")
    public List<OnboardTrainNodeState> onboardSubsystems() {
        return onboardTrainSubsystemManager.nodeStates();
    }

    @GetMapping("/runtime-health")
    public VehicleRuntimeStatusResponse runtimeHealth() {
        // 车辆运行时状态由中央代理输出，前端不直接访问外部 9300 服务。
        return vehicleRuntimeIntegrationService.status();
    }

    private String maintenanceState(int faultLevel) {
        if (faultLevel >= 3) {
            return "REPAIR_REQUIRED";
        }
        if (faultLevel > 0) {
            return "INSPECTION_REQUIRED";
        }
        return "NONE";
    }
}
