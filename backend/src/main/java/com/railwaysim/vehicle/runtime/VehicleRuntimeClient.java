package com.railwaysim.vehicle.runtime;

import com.railwaysim.train.TrainState;
import java.util.List;

public interface VehicleRuntimeClient {

    VehicleRuntimeHealth health();

    VehicleRuntimeHealth bootstrap(VehicleRuntimeBootstrapRequest request);

    VehicleRuntimeInstanceState registerTrain(TrainState train);

    void removeTrain(String trainId);

    void clear();

    List<VehicleRuntimeInstanceState> instances();

    VehicleRuntimeStepResponse stepFleet(VehicleRuntimeStepRequest request);

    /** 转发司控台 PLC 输入到 9300（仅在 EXTERNAL_HTTP 模式下使用）。 */
    default void forwardPlcInput(String trainId, byte[] payload) {
        // 默认空实现，子类按需覆盖
    }
}
