package com.railwaysim.api;

import com.railwaysim.api.dto.VehicleMaintenanceStateResponse;
import com.railwaysim.train.TrainManager;
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
    private final VehicleRuntimeIntegrationService vehicleRuntimeIntegrationService;

    public VehicleController(
        TrainManager trainManager,
        VehicleRuntimeIntegrationService vehicleRuntimeIntegrationService
    ) {
        this.trainManager = trainManager;
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

    @GetMapping("/runtime-health")
    public VehicleRuntimeStatusResponse runtimeHealth() {
        return vehicleRuntimeIntegrationService.status();
    }

    private String maintenanceState(int faultLevel) {
        if (faultLevel >= 3) return "REPAIR_REQUIRED";
        if (faultLevel > 0) return "INSPECTION_REQUIRED";
        return "NONE";
    }
}
