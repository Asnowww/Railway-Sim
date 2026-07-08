package com.railwaysim.power;

import java.time.Instant;
import java.util.List;

public record SubstationStateView(
    String id,
    String name,
    String supplyMode,
    String availability,
    List<SubstationDeviceView> devices,
    List<String> sectionIds,
    String dataQuality,
    Instant updatedAt
) {
}
