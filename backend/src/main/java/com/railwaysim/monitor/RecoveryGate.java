package com.railwaysim.monitor;

import java.util.List;

public record RecoveryGate(
    boolean accepted,
    boolean runIdMatched,
    boolean tickMatched,
    boolean topologyHashMatched,
    boolean configHashMatched,
    boolean modelVersionMatched,
    boolean parameterVersionMatched,
    List<String> rejectionReasons
) {
    public RecoveryGate {
        rejectionReasons = rejectionReasons == null ? List.of() : List.copyOf(rejectionReasons);
    }
}
