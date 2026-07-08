package com.railwaysim.api;

import com.railwaysim.api.dto.PowerEnergyResponse;
import com.railwaysim.api.dto.TrainEnergyResponse;
import com.railwaysim.power.PowerSectionState;
import com.railwaysim.power.PowerService;
import com.railwaysim.train.TrainManager;
import com.railwaysim.train.TrainState;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/energy")
@CrossOrigin
public class EnergyController {

    private final TrainManager trainManager;
    private final PowerService powerService;

    public EnergyController(TrainManager trainManager, PowerService powerService) {
        this.trainManager = trainManager;
        this.powerService = powerService;
    }

    @GetMapping("/trains")
    public List<TrainEnergyResponse> trainEnergy() {
        return trainManager.states().stream()
            .map(train -> new TrainEnergyResponse(
                train.id(),
                train.energyConsumedKwh(),
                train.energyRegeneratedKwh(),
                train.energyConsumedKwh() - train.energyRegeneratedKwh(),
                "CURRENT_SIMULATION",
                train.dataQuality(),
                Instant.now()
            ))
            .toList();
    }

    @GetMapping("/power-sections")
    public PowerEnergyResponse powerSectionEnergy() {
        List<PowerSectionState> sections = powerService.states();
        return new PowerEnergyResponse(
            sections.stream().mapToDouble(PowerSectionState::loadWatts).sum(),
            sections.stream().mapToDouble(PowerSectionState::regenPowerWatts).sum(),
            sections.stream().mapToDouble(PowerSectionState::absorbedRegenPowerWatts).sum(),
            sections.stream().mapToDouble(PowerSectionState::unabsorbedRegenPowerWatts).sum(),
            "CURRENT_SIMULATION",
            sections.stream().allMatch(section -> "GOOD".equals(section.dataQuality())) ? "GOOD" : "DEGRADED",
            Instant.now(),
            sections.stream()
                .map(section -> new PowerEnergyResponse.PowerSectionEnergy(
                    section.id(),
                    section.name(),
                    section.loadWatts(),
                    section.regenPowerWatts(),
                    section.absorbedRegenPowerWatts(),
                    section.unabsorbedRegenPowerWatts(),
                    section.dataQuality()
                ))
                .toList()
        );
    }
}
