package com.railwaysim.dispatch.integration;

import com.railwaysim.dispatch.DispatchCommand;
import com.railwaysim.dispatch.DispatchService;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TrainManagerPublisher implements DispatchCommandPublisher {

    private final DispatchService dispatchService;

    public TrainManagerPublisher(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Override
    public void publish(List<DispatchCommand> commands) {
        dispatchService.markCommandsSent(commands);
    }
}
