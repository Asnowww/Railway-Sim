package com.railwaysim.dispatch;

import com.railwaysim.train.TrainState;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class DispatchService {

    private final List<DispatchCommand> pendingCommands = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        reset();
    }

    public void reset() {
        pendingCommands.clear();
    }

    public List<DispatchCommand> pendingCommands() {
        return List.copyOf(pendingCommands);
    }

    public void submit(DispatchCommand command) {
        pendingCommands.add(command);
    }

    /**
     * 取出并清除指定类型的所有待处理调度指令。
     * 用于联锁等子系统在约束计算前拦截特定指令。
     */
    public List<DispatchCommand> drainCommandsOfType(String commandType) {
        List<DispatchCommand> matched = pendingCommands.stream()
            .filter(cmd -> commandType.equals(cmd.commandType()))
            .toList();
        pendingCommands.removeAll(matched);
        return matched;
    }

    public List<DispatchConstraint> constraintsForTrains(List<TrainState> trains) {
        Map<String, List<DispatchCommand>> commandsByTrain = pendingCommands.stream()
            .filter(command -> command.trainId() != null && !command.trainId().isBlank())
            .collect(Collectors.groupingBy(DispatchCommand::trainId));
        return trains.stream()
            .map(train -> constraintForTrain(train.id(), commandsByTrain.getOrDefault(train.id(), List.of())))
            .toList();
    }

    private DispatchConstraint constraintForTrain(String trainId, List<DispatchCommand> commands) {
        if (commands.isEmpty()) {
            return DispatchConstraint.none(trainId);
        }

        boolean holdTrain = false;
        double speedFactor = 1.0;
        Double targetSpeed = null;
        List<String> reasons = new ArrayList<>();
        for (DispatchCommand command : commands) {
            switch (command.commandType()) {
                case "HOLD", "HOLD_TRAIN" -> {
                    holdTrain = true;
                    reasons.add(reason(command));
                }
                case "SPEED_FACTOR", "LIMIT_FACTOR" -> {
                    speedFactor = Math.min(speedFactor, parseDoubleOrDefault(command.detail(), 1.0));
                    reasons.add(reason(command));
                }
                case "SPEED_LIMIT", "TEMP_SPEED_LIMIT" -> {
                    double parsedLimit = parseDoubleOrDefault(command.detail(), Double.NaN);
                    if (!Double.isNaN(parsedLimit)) {
                        targetSpeed = targetSpeed == null ? parsedLimit : Math.min(targetSpeed, parsedLimit);
                    }
                    reasons.add(reason(command));
                }
                default -> reasons.add(reason(command));
            }
        }
        return new DispatchConstraint(
            trainId,
            holdTrain,
            Math.max(0, Math.min(1, speedFactor)),
            targetSpeed,
            String.join("; ", reasons)
        );
    }

    private String reason(DispatchCommand command) {
        if (command.detail() == null || command.detail().isBlank()) {
            return command.commandType();
        }
        return command.commandType() + ":" + command.detail();
    }

    private double parseDoubleOrDefault(String text, double fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
