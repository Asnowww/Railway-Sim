package com.railwaysim.vehicle.external;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StubRtLabClient implements RtLabClient {

    private final List<String> lifecycleCalls = new ArrayList<>();
    private Map<String, Double> lastParameters = new LinkedHashMap<>();

    @Override
    public void opalOpenProject(String projectPath) {
        lifecycleCalls.add("OpalOpenProject:" + (projectPath == null ? "" : projectPath));
    }

    @Override
    public void opalLoad() {
        lifecycleCalls.add("OpalLoad");
    }

    @Override
    public void opalExecute() {
        lifecycleCalls.add("OpalExecute");
    }

    @Override
    public void opalReset() {
        lifecycleCalls.add("OpalReset");
    }

    @Override
    public void opalCloseProject() {
        lifecycleCalls.add("OpalCloseProject");
    }

    @Override
    public void opalSetParametersByName(Map<String, Double> valuesByPath) {
        lifecycleCalls.add("OpalSetParametersByName:" + valuesByPath.size());
        lastParameters = new LinkedHashMap<>(valuesByPath);
    }

    public List<String> lifecycleCalls() {
        return List.copyOf(lifecycleCalls);
    }

    public Map<String, Double> lastParameters() {
        return Map.copyOf(lastParameters);
    }
}
