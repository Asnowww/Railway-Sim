package com.railwaysim.vehicleruntime.model;

import java.time.Instant;
import java.util.List;

/**
 * 车辆运行时调用供电仿真 state/query 的请求体。
 */
public record PowerNetworkStateQueryRequest(
    Instant requestedAt,
    List<PowerNetworkSectionLoadRequest> sectionLoads
) {
    public PowerNetworkStateQueryRequest(List<PowerNetworkSectionLoadRequest> sectionLoads) {
        this(Instant.now(), sectionLoads == null ? List.of() : List.copyOf(sectionLoads));
    }
}
