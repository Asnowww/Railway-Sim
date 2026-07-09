package com.railwaysim.signal.vision;

import static org.assertj.core.api.Assertions.assertThat;
import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.signal.SignalAspect;
import com.railwaysim.signal.SignalState;
import com.railwaysim.track.SwitchPosition;
import com.railwaysim.track.SwitchState;
import com.railwaysim.track.TrackOccupancy;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.train.ExternalTrainControlSession;
import com.railwaysim.train.TrainEntity;
import com.railwaysim.vehicle.external.ExternalTrainDirection;
import com.railwaysim.vehicle.protocol.TrainOperationalTelemetry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisionUdpPacketBuilderTests {

    @Test
    void buildsSelectedTrainVisionPacketFromVehiclePutStateAndRuntimeContext() {
        TrackSegmentState segmentA = segment("SEG-A", 7, 0, 200);
        TrackSegmentState segmentB = segment("SEG-B", 8, 200, 400);
        OperationalLineData lineData = lineData();
        StaticInfrastructureCatalog catalog = new StaticInfrastructureCatalog(lineData, null);
        VisionVehicleStateStore store = new VisionVehicleStateStore();
        TrainEntity selected = new TrainEntity("TR-001", "demo", 50, 120, 0.42);
        selected.applyOperationalTelemetry(new TrainOperationalTelemetry(
            1,
            4.0,
            50,
            ExternalTrainDirection.DOWN,
            25_200,
            0,
            false,
            6,
            6
        ));
        TrainEntity other = new TrainEntity("TR-002", "demo", 220, 120, 0.42);
        other.applyOperationalTelemetry(new TrainOperationalTelemetry(
            2,
            3.21,
            222.22,
            ExternalTrainDirection.UP,
            25_200,
            0,
            false,
            6,
            6
        ));
        List<com.railwaysim.train.TrainState> trains = List.of(
            selected.state(ExternalTrainControlSession.inService("TR-001", 1, 50, ExternalTrainDirection.DOWN)),
            other.state(ExternalTrainControlSession.inService("TR-002", 1, 222.22, ExternalTrainDirection.UP))
        );
        List<SignalState> signalStates = List.of(
            new SignalState("SIG-A", "SEG-A", 0, SignalAspect.RED, "TR-001"),
            new SignalState("SIG-B", "SEG-B", 200, SignalAspect.GREEN, null)
        );
        List<SwitchState> switchStates = List.of(
            new SwitchState("SW-1", "N-1", SwitchPosition.REVERSE, false, "SEG-B")
        );
        List<TrackSegmentState> trackStates = List.of(segmentA, segmentB);
        store.put("TR-001", new VisionVehicleStateRequest(
            10.0,
            null,
            50,
            123.456,
            "SEG-A",
            1,
            "TRACTION",
            "HIGH",
            null,
            30
        ));
        VisionUdpPacketBuilder builder = new VisionUdpPacketBuilder(
            () -> trains,
            () -> signalStates,
            () -> switchStates,
            () -> trackStates,
            position -> position < 200 ? segmentA : segmentB,
            catalog,
            store
        );

        VisionUdpPacket packet = builder.build("TR-001");

        assertThat(packet.signalCount()).isEqualTo(2);
        assertThat(packet.switchCount()).isEqualTo(1);
        assertThat(packet.otherTrainCount()).isEqualTo(1);
        ByteBuffer buffer = ByteBuffer.wrap(packet.payload()).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(buffer.getInt()).isEqualTo(1);
        assertThat(Byte.toUnsignedInt(buffer.get())).isEqualTo(2);
        assertThat(Byte.toUnsignedInt(buffer.get())).isEqualTo(0x01);
        assertThat(Byte.toUnsignedInt(buffer.get())).isEqualTo(0x04);
        assertThat(Byte.toUnsignedInt(buffer.get())).isEqualTo(1);
        assertThat(Byte.toUnsignedInt(buffer.get())).isEqualTo(0x02);
        assertThat(buffer.getInt()).isEqualTo(10_000);
        assertThat(Short.toUnsignedInt(buffer.getShort())).isEqualTo(30);
        assertThat(Byte.toUnsignedInt(buffer.get())).isEqualTo(0x11);
        assertThat(Byte.toUnsignedInt(buffer.get())).isEqualTo(50);
        assertThat(buffer.getInt()).isEqualTo(123_456);
        assertThat(Short.toUnsignedInt(buffer.getShort())).isEqualTo(7);
        assertThat(buffer.get()).isEqualTo((byte) 1);
        assertThat(Byte.toUnsignedInt(buffer.get())).isEqualTo(1);
        assertThat(buffer.getInt()).isEqualTo(222_220);
        assertThat(Short.toUnsignedInt(buffer.getShort())).isEqualTo(8);
        assertThat(buffer.get()).isEqualTo((byte) -1);
        assertThat(Short.toUnsignedInt(buffer.getShort())).isEqualTo(321);
    }

    private TrackSegmentState segment(String id, int rawSegmentId, double startMeters, double endMeters) {
        return new TrackSegmentState(id, startMeters, endMeters, 22.2, TrackOccupancy.FREE, "", "", "main");
    }

    private OperationalLineData lineData() {
        return new OperationalLineData(
            "1",
            "vision-test",
            List.of(),
            List.of(
                trackSegment("SEG-A", 7, 0, 200),
                trackSegment("SEG-B", 8, 200, 400)
            ),
            List.of(),
            List.of(),
            List.of(new OperationalLineData.SwitchDefinition(
                "SW-1",
                "SW-1",
                "",
                "",
                "SEG-A",
                "SEG-B",
                "N-1",
                22.2,
                "0x0101",
                "NORMAL"
            )),
            List.of(),
            List.of(),
            List.of(
                signal("0x0121", "SEG-A", 0),
                signal("0x0120", "SEG-B", 200)
            ),
            List.of(),
            List.of()
        );
    }

    private OperationalLineData.TrackSegmentDefinition trackSegment(
        String id,
        int rawSegmentId,
        double startMeters,
        double endMeters
    ) {
        return new OperationalLineData.TrackSegmentDefinition(
            id,
            rawSegmentId,
            startMeters,
            endMeters,
            endMeters - startMeters,
            22.2,
            0,
            0,
            0,
            0,
            List.of(),
            List.of(),
            "",
            "",
            "main"
        );
    }

    private OperationalLineData.SignalDefinition signal(String id, String segmentId, double positionMeters) {
        return new OperationalLineData.SignalDefinition(
            id,
            id,
            "",
            "",
            segmentId,
            positionMeters,
            "",
            "",
            id
        );
    }
}
