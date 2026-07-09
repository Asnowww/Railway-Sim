package com.railwaysim.power.external;

import java.util.List;

public interface ExternalPowerNetworkClient {

    void bootstrap(PowerNetworkBootstrapRequest request);

    PowerNetworkStateSnapshot currentState();

    PowerNetworkStateSnapshot queryState(PowerNetworkStateQueryRequest request);

    List<PowerNetworkEventPayload> events();

    PowerNetworkOperationResult operate(PowerNetworkOperationRequest request);
}
