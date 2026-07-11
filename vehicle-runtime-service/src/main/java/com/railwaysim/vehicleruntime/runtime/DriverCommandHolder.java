package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.model.DriverControlCommandSnapshot;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class DriverCommandHolder {

    private static volatile DriverCommandHolder instance;

    public DriverCommandHolder() {
        instance = this;
    }

    public static DriverCommandHolder getInstance() {
        if (instance == null) {
            instance = new DriverCommandHolder();
        }
        return instance;
    }

    private final Map<String, DriverControlCommandSnapshot> commands = new ConcurrentHashMap<>();

    public void store(DriverControlCommandSnapshot cmd) {
        if (cmd != null) commands.put(cmd.trainId(), cmd);
    }

    public DriverControlCommandSnapshot latest(String trainId) {
        DriverControlCommandSnapshot cmd = commands.get(trainId);
        if (cmd == null) return null;
        if (cmd.expired(Instant.now())) { commands.remove(trainId); return null; }
        return cmd;
    }

    public void clear() { commands.clear(); }
}
