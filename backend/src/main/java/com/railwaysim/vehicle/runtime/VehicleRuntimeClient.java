package com.railwaysim.vehicle.runtime;

import com.railwaysim.train.TrainState;
import java.util.List;
import java.util.Map;
import com.railwaysim.vehicle.telemetry.VehicleTelemetryRequest;
import com.railwaysim.vehicle.telemetry.VehicleTelemetryResponse;

public interface VehicleRuntimeClient {

    VehicleRuntimeHealth health();

    VehicleRuntimeHealth bootstrap(VehicleRuntimeBootstrapRequest request);

    VehicleRuntimeInstanceState registerTrain(TrainState train);

    void removeTrain(String trainId);

    void clear();

    List<VehicleRuntimeInstanceState> instances();

    VehicleRuntimeStepResponse stepFleet(VehicleRuntimeStepRequest request);

    default VehicleTelemetryResponse forwardTelemetry(VehicleTelemetryRequest request) {
        throw new UnsupportedOperationException("vehicle telemetry forwarding is not configured");
    }

    /** 转发司控台 PLC 输入到 9300（仅在 EXTERNAL_HTTP 模式下使用）。 */
    default Map<String, Object> forwardPlcInput(String trainId, byte[] payload) {
        throw new UnsupportedOperationException("PLC forwarding is not configured");
    }

}
