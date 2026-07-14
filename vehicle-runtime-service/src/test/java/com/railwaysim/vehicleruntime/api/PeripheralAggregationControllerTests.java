package com.railwaysim.vehicleruntime.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.railwaysim.vehicleruntime.protocol.PeripheralChannel;
import com.railwaysim.vehicleruntime.protocol.PeripheralFrame;
import com.railwaysim.vehicleruntime.protocol.PeripheralFrameCodec;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class PeripheralAggregationControllerTests {

    @Test
    void rejectsDisplayOutputOnVehicleInputDirection() {
        PeripheralAggregationController controller = new PeripheralAggregationController(
            mock(DriverCabInputController.class)
        );
        byte[] frame = new PeripheralFrameCodec().encode(new PeripheralFrame(
            PeripheralChannel.NETWORK_SCREEN_OUTPUT, 9, 0, "TR-001", new byte[570]
        ));

        ResponseEntity<?> response = controller.accept(frame);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(((Map<?, ?>) response.getBody()).get("reasonCode"))
            .isEqualTo("CHANNEL_DIRECTION_INVALID");
    }
}
