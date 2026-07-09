package com.railwaysim.dispatch.command;

import com.railwaysim.dispatch.DispatchCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

@Component
public class InMemoryCommandRecordStore implements CommandRecordStore {

    private final List<DispatchCommand> records = new CopyOnWriteArrayList<>();

    @Override
    public void save(DispatchCommand command) {
        records.add(command);
    }

    @Override
    public void update(DispatchCommand command) {
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).id().equals(command.id())) {
                records.set(i, command);
                return;
            }
        }
        records.add(command);
    }

    @Override
    public List<DispatchCommand> list(String simulationRunId) {
        List<DispatchCommand> filtered = new ArrayList<>();
        for (DispatchCommand command : records) {
            Object runId = command.payload() == null ? null : command.payload().get("simulationRunId");
            if (simulationRunId.equals(runId)) {
                filtered.add(command);
            }
        }
        return filtered;
    }

    public void clear() {
        records.clear();
    }
}
