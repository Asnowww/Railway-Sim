package com.railwaysim.train;

import java.time.Instant;

public record TrainFaultRecord(
    String trainId,
    String faultCode,
    int faultLevel,
    String selfCheckStatus,
    String availableOperationMode,
    String state,
    String detail,
    String traceId,
    Instant raisedAt,
    Instant clearedAt
) {
}
