package com.railwaysim.api;

import com.railwaysim.api.dto.VehicleMaintenanceStateResponse;
import com.railwaysim.train.TrainManager;
import com.railwaysim.vehicle.onboard.OnboardTrainNodeState;
import com.railwaysim.vehicle.onboard.OnboardTrainSubsystemManager;
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

    public VehicleController(
        TrainManager trainManager,
        OnboardTrainSubsystemManager onboardTrainSubsystemManager
    ) {
        this.trainManager = trainManager;
        this.onboardTrainSubsystemManager = onboardTrainSubsystemManager;
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
