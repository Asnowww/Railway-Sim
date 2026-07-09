package com.railwaysim.localnet;

import java.time.Instant;

public record LocalNetReplayResult(
    String adapterId,
    ProtocolFamily family,
    boolean accepted,
    String summary,
    String error,
    Instant replayedAt
) {
    public LocalNetReplayResult {
        summary = summary == null ? "" : summary;
        error = error == null ? "" : error;
        replayedAt = replayedAt == null ? Instant.now() : replayedAt;
    }
}
