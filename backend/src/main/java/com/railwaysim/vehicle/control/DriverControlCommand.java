package com.railwaysim.vehicle.control;

import java.time.Instant;
import java.util.UUID;

/**
 * 司机台控制命令 — 不可变值对象。
 * <p>
 * 每个命令来自一次 PLC 输入解析结果，携带完整溯源信息。
 * 命令过期后进入 STALE 状态，不再参与仲裁。
 */
public record DriverControlCommand(
    String commandId,
    String trainId,
    int sequenceNo,
    Instant receivedAt,
    Instant expiresAt,
    double tractionCommand,         // 0.0 ~ 1.0
    double brakeCommand,            // 0.0 ~ 1.0
    boolean emergencyBrake,
    double direction,               // -1.0 (后退), 0.0 (零位), 1.0 (前进)
    boolean doorOpenRequest,
    boolean atoRequest,
    String operationMode,           // "MANUAL" / "AUTO" / "INTELLIGENT"
    String traceId,
    byte[] rawPacket
) {
    public DriverControlCommand {
        commandId = commandId != null ? commandId : UUID.randomUUID().toString();
        traceId = traceId != null ? traceId : UUID.randomUUID().toString();
    }

    public boolean isExpired(Instant now) {
        return now != null && expiresAt != null && now.isAfter(expiresAt);
    }

    public boolean isTractionCommandValid() {
        return tractionCommand >= 0.0 && tractionCommand <= 1.0;
    }

    public boolean isBrakeCommandValid() {
        return brakeCommand >= 0.0 && brakeCommand <= 1.0;
    }

    public boolean hasValidDirection() {
        return direction >= -1.0 && direction <= 1.0;
    }
}
