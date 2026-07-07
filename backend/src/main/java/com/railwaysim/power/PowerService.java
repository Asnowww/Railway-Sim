package com.railwaysim.power;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PowerService {

    public List<PowerSectionState> states() {
        return List.of(
            new PowerSectionState("P01", "南段供电分区", 0, 2500, 1500, 0, "ENERGIZED"),
            new PowerSectionState("P02", "北段供电分区", 2500, 5000, 1500, 0, "ENERGIZED")
        );
    }
}

