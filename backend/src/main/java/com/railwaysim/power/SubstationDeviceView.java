package com.railwaysim.power;

import java.util.List;

public record SubstationDeviceView(
    String id,
    String name,
    String deviceType,
    String state,
    boolean available,
    List<String> affectsSectionIds
) {
}
