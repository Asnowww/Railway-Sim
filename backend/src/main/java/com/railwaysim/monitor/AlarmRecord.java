package com.railwaysim.monitor;

import java.time.Instant;

public record AlarmRecord(
    String id,
    String simulationRunId,
    String alarmCode,
    String sourceModule,
    String locationRef,
    int level,
    String title,
    String detail,
    AlarmLifecycleState state,
    Instant raisedAt,
    Instant lastSeenAt,
    Instant acknowledgedAt,
    String acknowledgedBy,
    Instant clearedAt
) {
    Alarm toAlarm() {
        return new Alarm(id, sourceModule, locationRef, level, title, detail, raisedAt,
            state == AlarmLifecycleState.ACKNOWLEDGED);
    }
}
