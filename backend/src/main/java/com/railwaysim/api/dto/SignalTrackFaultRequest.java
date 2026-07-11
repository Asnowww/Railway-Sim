package com.railwaysim.api.dto;

import com.railwaysim.signal.SignalTrackFaultType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SignalTrackFaultRequest(
    @NotBlank String sourceId,
    @NotNull SignalTrackFaultType faultType,
    String operator,
    String reason,
    String traceId
) {
    public String normalizedOperator() {
        return operator == null || operator.isBlank() ? "system" : operator;
    }

    public String normalizedReason() {
        return reason == null ? "" : reason;
    }

    public String normalizedTraceId() {
        return traceId == null ? "" : traceId;
    }
}
