package com.railwaysim.dispatch.integration;

import com.railwaysim.dispatch.DispatchCommand;
import com.railwaysim.train.TrainManager;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TrainManagerPublisher implements DispatchCommandPublisher {

    private final TrainManager trainManager;

    public TrainManagerPublisher(TrainManager trainManager) {
        this.trainManager = trainManager;
    }

    @Override
    public void publish(List<DispatchCommand> commands) {
        trainManager.stageCommands(commands);
    }
}
