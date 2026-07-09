package com.railwaysim.power.external;

import java.time.Instant;
import java.util.List;

public record PowerNetworkStateQueryRequest(
    Instant requestedAt,
    List<PowerNetworkSectionLoadRequest> sectionLoads
) {
    public PowerNetworkStateQueryRequest {
        requestedAt = requestedAt == null ? Instant.now() : requestedAt;
        sectionLoads = sectionLoads == null ? List.of() : List.copyOf(sectionLoads);
    }

    public PowerNetworkStateQueryRequest(List<PowerNetworkSectionLoadRequest> sectionLoads) {
        this(Instant.now(), sectionLoads);
    }
}
