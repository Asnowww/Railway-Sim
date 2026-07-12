package com.railwaysim.dispatch.plan;

import java.util.List;

public record CirculationPlan(
    String id,
    String rollingStockId,
    List<String> serviceIds
) {
    public CirculationPlan {
        serviceIds = serviceIds == null ? List.of() : List.copyOf(serviceIds);
    }
}
