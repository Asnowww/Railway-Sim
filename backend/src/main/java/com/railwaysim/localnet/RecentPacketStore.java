package com.railwaysim.localnet;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class RecentPacketStore {

    private final int limit;
    private final ArrayDeque<LocalNetPacketRecord> records;
    private final AtomicLong inboundPackets = new AtomicLong();
    private final AtomicLong outboundPackets = new AtomicLong();
    private final AtomicLong errorPackets = new AtomicLong();
    private volatile String lastMessage = "";
    private volatile String lastError = "";
    private volatile Instant lastPacketAt;

    public RecentPacketStore(int limit) {
        this.limit = Math.max(1, limit);
        this.records = new ArrayDeque<>(this.limit);
    }

    public synchronized LocalNetPacketRecord record(
        ProtocolFamily family,
        String adapterId,
        PacketDirection direction,
        int byteLength,
        String summary,
        String status,
        String error
    ) {
        LocalNetPacketRecord record = new LocalNetPacketRecord(
            family,
            adapterId,
            direction,
            byteLength,
            summary,
            status,
            error,
            Instant.now()
        );
        if (records.size() == limit) {
            records.removeFirst();
        }
        records.addLast(record);
        if (direction == PacketDirection.OUTBOUND) {
            outboundPackets.incrementAndGet();
        } else {
            inboundPackets.incrementAndGet();
        }
        if (!"OK".equals(status)) {
            errorPackets.incrementAndGet();
            lastError = record.error();
        }
        lastMessage = record.summary();
        lastPacketAt = record.recordedAt();
        return record;
    }

    public LocalNetHealth health(String adapterId, ProtocolFamily family, boolean configured, boolean enabled, boolean running) {
        return new LocalNetHealth(
            adapterId,
            family,
            configured,
            enabled,
            running,
            inboundPackets.get(),
            outboundPackets.get(),
            errorPackets.get(),
            lastMessage,
            lastError,
            lastPacketAt,
            snapshot()
        );
    }

    public synchronized List<LocalNetPacketRecord> snapshot() {
        return List.copyOf(new ArrayList<>(records));
    }
}
