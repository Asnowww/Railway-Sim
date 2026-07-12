package com.railwaysim.dispatch.route;

public record InterlockingFeedback(
    boolean accepted,
    String resultCode,
    String failureCategory,
    boolean retryable,
    String routeId,
    String interlockingState,
    String reason
) {
}
