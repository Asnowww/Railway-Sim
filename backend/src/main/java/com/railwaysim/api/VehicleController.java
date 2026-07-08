package com.railwaysim.api;

import com.railwaysim.api.dto.VehicleMaintenanceStateResponse;
import com.railwaysim.train.TrainManager;
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

    public VehicleController(TrainManager trainManager) {
        this.trainManager = trainManager;
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
