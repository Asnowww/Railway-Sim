package com.railwaysim.api.dto;

import java.util.List;

public record SubstationDeviceResponse(
    String id,
    String name,
    String deviceType,
    String state,
    boolean available,
    List<String> affectsSectionIds
) {
}
