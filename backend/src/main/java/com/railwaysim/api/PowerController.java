package com.railwaysim.api;

import com.railwaysim.api.dto.FaultMutationRequest;
import com.railwaysim.api.dto.OperationLogEntry;
import com.railwaysim.api.dto.PowerEnergyResponse;
import com.railwaysim.api.dto.PowerMaintenanceLockResponse;
import com.railwaysim.power.PowerSectionEvent;
import com.railwaysim.power.PowerSectionState;
import com.railwaysim.power.PowerService;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/power")
@CrossOrigin
public class PowerController {

    private static final String CONFIRM_TOKEN = "SIMULATION_CONFIRM";

    private final PowerService powerService;
    private final ApiOperationLogService operationLogService;

    public PowerController(PowerService powerService, ApiOperationLogService operationLogService) {
        this.powerService = powerService;
        this.operationLogService = operationLogService;
    }

    @GetMapping("/sections")
    public List<PowerSectionState> sections() {
        return powerService.states();
    }

    @GetMapping("/sections/{sectionId}")
    public PowerSectionState section(@PathVariable String sectionId) {
        try {
            return powerService.section(sectionId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @GetMapping("/sections/{sectionId}/events")
    public List<PowerSectionEvent> events(@PathVariable String sectionId) {
        section(sectionId);
        return powerService.eventsForSection(sectionId);
    }

    @GetMapping("/energy")
    public PowerEnergyResponse energy() {
        return energyResponse(powerService.states());
    }

    @GetMapping("/maintenance-locks")
    public List<PowerMaintenanceLockResponse> maintenanceLocks() {
        return powerService.states().stream()
            .map(section -> new PowerMaintenanceLockResponse(
                section.id(),
                section.name(),
                section.maintenanceState(),
                section.lockoutState(),
                section.breakerStatus(),
                section.status(),
                section.updatedAt()
            ))
            .toList();
    }

    @PostMapping("/sections/{sectionId}/faults")
    public PowerSectionState injectFault(
        @PathVariable String sectionId,
        @RequestBody FaultMutationRequest request
    ) {
        requireConfirm(request);
        PowerSectionState before = section(sectionId);
        powerService.injectPowerFault(sectionId, requiredFaultType(request));
        PowerSectionState after = section(sectionId);
        OperationLogEntry operation = operationLogService.record(
            request.normalizedOperator(),
            "POWER_FAULT_INJECT",
            "power-section:" + sectionId,
            before.status(),
            after.status(),
            request.normalizedReason(),
            request.normalizedTraceId()
        );
        operationLogService.recordPowerOperation(operation, sectionId);
        return after;
    }

    @PostMapping("/sections/{sectionId}/faults/clear")
    public PowerSectionState clearFault(
        @PathVariable String sectionId,
        @RequestBody FaultMutationRequest request
    ) {
        requireConfirm(request);
        PowerSectionState before = section(sectionId);
        powerService.clearPowerFault(sectionId);
        PowerSectionState after = section(sectionId);
        OperationLogEntry operation = operationLogService.record(
            request.normalizedOperator(),
            "POWER_FAULT_CLEAR",
            "power-section:" + sectionId,
            before.status(),
            after.status(),
            request.normalizedReason(),
            request.normalizedTraceId()
        );
        operationLogService.recordPowerOperation(operation, sectionId);
        return after;
    }

    private PowerEnergyResponse energyResponse(List<PowerSectionState> sections) {
        List<PowerEnergyResponse.PowerSectionEnergy> sectionEnergy = sections.stream()
            .map(section -> new PowerEnergyResponse.PowerSectionEnergy(
                section.id(),
                section.name(),
                section.loadWatts(),
                section.regenPowerWatts(),
                section.absorbedRegenPowerWatts(),
                section.unabsorbedRegenPowerWatts(),
                section.dataQuality()
            ))
            .toList();
        return new PowerEnergyResponse(
            sections.stream().mapToDouble(PowerSectionState::loadWatts).sum(),
            sections.stream().mapToDouble(PowerSectionState::regenPowerWatts).sum(),
            sections.stream().mapToDouble(PowerSectionState::absorbedRegenPowerWatts).sum(),
            sections.stream().mapToDouble(PowerSectionState::unabsorbedRegenPowerWatts).sum(),
            "CURRENT_SIMULATION",
            sections.stream().allMatch(section -> "GOOD".equals(section.dataQuality())) ? "GOOD" : "DEGRADED",
            Instant.now(),
            sectionEnergy
        );
    }

    private void requireConfirm(FaultMutationRequest request) {
        if (request == null || !CONFIRM_TOKEN.equals(request.confirmToken())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirmToken must be SIMULATION_CONFIRM");
        }
    }

    private String requiredFaultType(FaultMutationRequest request) {
        if (request.faultType() == null || request.faultType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "faultType is required");
        }
        return request.faultType();
    }
}
