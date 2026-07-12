package com.railwaysim.monitor;

public enum ServiceHealthState {
    UP,
    DEGRADED,
    STALE,
    FALLBACK,
    RECOVERING
}
