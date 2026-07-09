package com.railwaysim.localnet;

import java.time.Instant;
import java.util.List;

public record LocalNetHealth(
    String adapterId,
    ProtocolFamily family,
    boolean configured,
    boolean enabled,
    boolean running,
    long inboundPackets,
    long outboundPackets,
    long errorPackets,
    String lastMessage,
    String lastError,
    Instant lastPacketAt,
    List<LocalNetPacketRecord> recentPackets
) {
    public LocalNetHealth {
        lastMessage = lastMessage == null ? "" : lastMessage;
        lastError = lastError == null ? "" : lastError;
        recentPackets = recentPackets == null ? List.of() : List.copyOf(recentPackets);
    }
}
