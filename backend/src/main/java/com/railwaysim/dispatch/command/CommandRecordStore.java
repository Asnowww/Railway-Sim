package com.railwaysim.dispatch.command;

import com.railwaysim.dispatch.DispatchCommand;
import java.util.List;

public interface CommandRecordStore {

    void save(DispatchCommand command);

    void update(DispatchCommand command);

    List<DispatchCommand> list(String simulationRunId);
}
