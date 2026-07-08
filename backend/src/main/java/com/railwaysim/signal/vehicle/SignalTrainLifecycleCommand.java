package com.railwaysim.signal.vehicle;

import java.util.List;

public record SignalTrainLifecycleCommand(
    SignalTrainLifecycleAction action,
    List<SignalTrainLifecycleTrainSpec> trains
) {
    public SignalTrainLifecycleCommand {
        if (action == null) {
            throw new IllegalArgumentException("action is required");
        }
        trains = trains == null ? List.of() : List.copyOf(trains);
        if (action != SignalTrainLifecycleAction.CLEAR && trains.isEmpty()) {
            throw new IllegalArgumentException("ADD/DELETE lifecycle commands require at least one train");
        }
        if (trains.size() > 255) {
            throw new IllegalArgumentException("lifecycle command train count must fit in one byte");
        }
    }

    public static SignalTrainLifecycleCommand add(List<SignalTrainLifecycleTrainSpec> trains) {
        return new SignalTrainLifecycleCommand(SignalTrainLifecycleAction.ADD, trains);
    }

    public static SignalTrainLifecycleCommand delete(List<SignalTrainLifecycleTrainSpec> trains) {
        return new SignalTrainLifecycleCommand(SignalTrainLifecycleAction.DELETE, trains);
    }

    public static SignalTrainLifecycleCommand clear() {
        return new SignalTrainLifecycleCommand(SignalTrainLifecycleAction.CLEAR, List.of());
    }
}
