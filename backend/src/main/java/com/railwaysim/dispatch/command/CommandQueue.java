package com.railwaysim.dispatch.command;

import com.railwaysim.dispatch.DispatchCommand;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CommandQueue {

    private final List<DispatchCommand> pending = new ArrayList<>();

    public synchronized void enqueue(List<DispatchCommand> commands) {
        pending.addAll(commands);
    }

    public synchronized List<DispatchCommand> drain() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<DispatchCommand> drained = List.copyOf(pending);
        pending.clear();
        return drained;
    }

    public synchronized List<DispatchCommand> peekPending() {
        return List.copyOf(pending);
    }

    public synchronized void clear() {
        pending.clear();
    }
}
