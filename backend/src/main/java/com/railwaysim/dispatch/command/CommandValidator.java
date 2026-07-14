package com.railwaysim.dispatch.command;

import com.railwaysim.dispatch.DispatchCommand;
import com.railwaysim.dispatch.route.RouteDispatchRecordStore;
import com.railwaysim.signal.MovementAuthority;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class CommandValidator {

    public List<DispatchCommand> validate(List<DispatchCommand> commands, List<MovementAuthority> authorities) {
        return validate(commands, authorities, Instant.now());
    }

    public List<DispatchCommand> validate(
        List<DispatchCommand> commands,
        List<MovementAuthority> authorities,
        Instant effectiveAt
    ) {
        List<DispatchCommand> validated = new ArrayList<>();
        for (DispatchCommand command : commands) {
            validated.add(validateOne(command, authorities, effectiveAt));
        }
        return validated;
    }

    private DispatchCommand validateOne(
        DispatchCommand command,
        List<MovementAuthority> authorities,
        Instant effectiveAt
    ) {
        if (RouteDispatchRecordStore.isRouteCommand(command)
            || RouteDispatchRecordStore.isRouteCancellation(command.commandType())) {
            if (command.trainId() == null || command.trainId().isBlank()) {
                return skip(command, "route command requires trainId");
            }
            String routeId = RouteDispatchRecordStore.routeIdFrom(command);
            if (routeId == null || routeId.isBlank()) {
                return skip(command, "route command requires routeId or detail");
            }
            String validUntil = stringPayload(command.payload(), "validUntil");
            if (validUntil != null && effectiveAt != null) {
                try {
                    if (!Instant.parse(validUntil).isAfter(effectiveAt)) {
                        return expire(command, "route command validity expired");
                    }
                } catch (RuntimeException exception) {
                    return skip(command, "route command validUntil must be an ISO-8601 instant");
                }
            }
        }
        if ("SPEED_BIAS".equals(command.commandType())) {
            double ratio = numberPayload(command.payload(), "speedBiasRatio", 1.0);
            if (ratio > 1.2 || ratio < 0.6) {
                return skip(command, "speed bias out of allowed range");
            }
            MovementAuthority authority = findAuthority(authorities, command.trainId());
            if (authority != null && authority.speedLimitMetersPerSecond() <= 0) {
                return skip(command, "invalid movement authority");
            }
        }
        if ("EXTEND_DWELL".equals(command.commandType()) || "SHORTEN_DWELL".equals(command.commandType())) {
            int delta = (int) numberPayload(command.payload(), "deltaDwellSec", 0);
            int absDelta = Math.abs(delta);
            if (absDelta > 10) {
                return skip(command, "dwell adjust exceeds per-step limit");
            }
        }
        return command;
    }

    private DispatchCommand skip(DispatchCommand command, String reason) {
        return withTerminalStatus(command, reason, CommandStatus.SKIPPED);
    }

    private DispatchCommand expire(DispatchCommand command, String reason) {
        return withTerminalStatus(command, reason, CommandStatus.EXPIRED);
    }

    private DispatchCommand withTerminalStatus(DispatchCommand command, String reason, String status) {
        Map<String, Object> payload = command.payload() == null
            ? new HashMap<>()
            : new HashMap<>(command.payload());
        payload.put("skipReason", reason);
        return new DispatchCommand(
            command.id(),
            command.trainId(),
            command.commandType(),
            payload,
            command.reason(),
            status,
            command.createdAt(),
            command.appliedAt()
        );
    }

    private MovementAuthority findAuthority(List<MovementAuthority> authorities, String trainId) {
        return authorities.stream()
            .filter(authority -> authority.trainId().equals(trainId))
            .findFirst()
            .orElse(null);
    }

    private double numberPayload(Map<String, Object> payload, String key, double defaultValue) {
        if (payload == null) {
            return defaultValue;
        }
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return defaultValue;
    }

    private String stringPayload(Map<String, Object> payload, String key) {
        if (payload == null || payload.get(key) == null) {
            return null;
        }
        return payload.get(key).toString();
    }
}
