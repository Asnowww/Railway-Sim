package com.railwaysim.dispatch.command;

import com.railwaysim.dispatch.DispatchCommand;
import com.railwaysim.dispatch.config.DispatchProperties;
import com.railwaysim.signal.MovementAuthority;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CommandValidator {

    private final DispatchProperties properties;

    public CommandValidator(DispatchProperties properties) {
        this.properties = properties;
    }

    public List<DispatchCommand> validate(List<DispatchCommand> commands, List<MovementAuthority> authorities) {
        List<DispatchCommand> validated = new ArrayList<>();
        for (DispatchCommand command : commands) {
            validated.add(validateOne(command, authorities));
        }
        return validated;
    }

    private DispatchCommand validateOne(DispatchCommand command, List<MovementAuthority> authorities) {
        if ("SPEED_BIAS".equals(command.commandType())) {
            double ratio = numberPayload(command.payload(), "speedBiasRatio", 1.0);
            if (ratio > 1.1 || ratio < 0.9) {
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
        Map<String, Object> payload = new java.util.HashMap<>(command.payload());
        payload.put("skipReason", reason);
        return new DispatchCommand(
            command.id(),
            command.trainId(),
            command.commandType(),
            payload,
            command.reason(),
            CommandStatus.SKIPPED,
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
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return defaultValue;
    }
}
