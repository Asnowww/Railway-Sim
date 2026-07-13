package com.railwaysim.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.railwaysim.signal.SignalService;
import com.railwaysim.signal.vision.VisionVehicleStateStore;
import com.railwaysim.train.TrainManager;
import com.railwaysim.vehicle.external.ExternalTrainDirection;
import com.railwaysim.vehicle.protocol.SignalTrainContentCodec;
import com.railwaysim.vehicle.protocol.TrainOperationalTelemetry;
import com.railwaysim.vehicle.telemetry.VehicleTelemetryGatewayService;
import com.railwaysim.vehicle.telemetry.VehicleTelemetryResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SignalVehicleInterfaceControllerTests {

    @Test
    void jsonAndBinaryIngressNormalizeToTheSameGatewayPayloadWithoutChangingCentralMirror() {
        TrainManager trainManager = mock(TrainManager.class);
        VehicleTelemetryGatewayService gateway = mock(VehicleTelemetryGatewayService.class);
        when(gateway.forward(any(), any())).thenReturn(new VehicleTelemetryResponse(true, List.of()));
        SignalVehicleInterfaceController controller = new SignalVehicleInterfaceController(
            trainManager, mock(SignalService.class), mock(VisionVehicleStateStore.class), gateway
        );
        TrainOperationalTelemetry telemetry = new TrainOperationalTelemetry(
            1, 12.5, 1860.2, ExternalTrainDirection.DOWN, 42_000, 1.5, false, 4, 4
        );

        controller.applyTelemetry(List.of(telemetry));
        controller.applyContentPacket(
            1, new SignalTrainContentCodec().encode(List.of(telemetry))
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TrainOperationalTelemetry>> payloads = ArgumentCaptor.forClass(List.class);
        verify(gateway, times(2)).forward(any(), payloads.capture());
        assertThat(payloads.getAllValues().get(0)).isEqualTo(payloads.getAllValues().get(1));
        verifyNoInteractions(trainManager);
    }
}
