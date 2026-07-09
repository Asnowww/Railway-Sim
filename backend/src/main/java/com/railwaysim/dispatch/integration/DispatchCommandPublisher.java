package com.railwaysim.dispatch.integration;

import com.railwaysim.dispatch.DispatchCommand;
import java.util.List;

public interface DispatchCommandPublisher {

    void publish(List<DispatchCommand> commands);
}
