package com.railwaysim.localnet.powerpoints;

import java.util.List;

public record PowerPointTable(List<PowerPointDefinition> points) {
    public PowerPointTable {
        points = points == null ? List.of() : List.copyOf(points);
    }
}
