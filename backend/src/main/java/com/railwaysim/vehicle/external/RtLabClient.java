package com.railwaysim.vehicle.external;

import java.util.Map;

public interface RtLabClient {

    void opalOpenProject(String projectPath);

    void opalLoad();

    void opalExecute();

    void opalReset();

    void opalCloseProject();

    void opalSetParametersByName(Map<String, Double> valuesByPath);
}
