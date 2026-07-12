package com.railwaysim.monitor;

import java.time.Instant;

public record Alarm(
    String id,
    String sourceModule,
    String locationRef,
    int level,
    String title,
    String detail,
    Instant raisedAt,
    boolean confirmed,
    FaultImpact impact
) {
    public Alarm(
        String id, String sourceModule, String locationRef, int level, String title,
        String detail, Instant raisedAt, boolean confirmed
    ) {
        this(id, sourceModule, locationRef, level, title, detail, raisedAt, confirmed, null);
    }
}
