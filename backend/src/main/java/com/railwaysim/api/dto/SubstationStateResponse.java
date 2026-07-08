package com.railwaysim.api.dto;

import java.time.Instant;
import java.util.List;

public record SubstationStateResponse(
    String id,
    String name,
    String supplyMode,
    String availability,
    List<SubstationDeviceResponse> devices,
    List<String> sectionIds,
    String dataQuality,
    Instant updatedAt
) {
}
