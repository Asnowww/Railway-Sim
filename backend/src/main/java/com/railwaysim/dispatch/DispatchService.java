package com.railwaysim.dispatch;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DispatchService {

    public List<DispatchCommand> pendingCommands() {
        return List.of();
    }
}

