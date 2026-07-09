package com.railwaysim.localnet.signal;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.signal.vehicle.SignalTrainLifecycleAction;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleCommand;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleCommandCodec;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleTrainSpec;
import com.railwaysim.train.TrainManager;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.external.ExternalTrainDirection;
import com.railwaysim.vehicle.protocol.SignalTrainContentCodec;
import com.railwaysim.vehicle.protocol.TrainOperationalTelemetry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SignalFrameDomainMapperTests {

    @Test
    void appliesRealtimeLifecyclePacketToTrainManager() {
        CapturingTrainManager trainManager = new CapturingTrainManager();
        SignalFrameDomainMapper mapper = new SignalFrameDomainMapper(trainManager);
        SignalTrainLifecycleCommandCodec codec = new SignalTrainLifecycleCommandCodec();
        byte[] payload = codec.encode(SignalTrainLifecycleCommand.add(
            List.of(SignalTrainLifecycleTrainSpec.add(3, 12, 640, ExternalTrainDirection.DOWN))
        ));

        SignalFrameDomainMapper.SignalInboundResult result = mapper.applyInbound(payload);

        assertThat(result.accepted()).isTrue();
        assertThat(result.summary()).contains("ADD").contains("trains=1");
        assertThat(trainManager.applied).isNotNull();
        assertThat(trainManager.applied.action()).isEqualTo(SignalTrainLifecycleAction.ADD);
    }

    @Test
    void appliesOperationalTelemetryFromDatabaseNodeTrainContentFrame() {
        CapturingTrainManager trainManager = new CapturingTrainManager();
        SignalFrameDomainMapper mapper = new SignalFrameDomainMapper(trainManager);
        SignalTrainContentCodec contentCodec = new SignalTrainContentCodec();
        byte[] content = contentCodec.encode(List.of(new TrainOperationalTelemetry(
            7,
            12.5,
            640.0,
            ExternalTrainDirection.DOWN,
            82000,
            2.0,
            false,
            4,
            5
        )));
        SignalDatabaseNodeFrame frame = new SignalDatabaseNodeFrame(
            SignalDatabaseNodeFrame.TRAIN_CONTENT,
            SignalDatabaseNodeFrameCodec.SIGNAL_SOURCE_ID,
            SignalDatabaseNodeFrameCodec.CENTRAL_SOURCE_ID,
            content
        );

        SignalFrameDomainMapper.SignalInboundResult result = mapper.applyInbound(new SignalDatabaseNodeFrameCodec().encode(frame));

        assertThat(result.accepted()).isTrue();
        assertThat(result.summary()).contains("operational telemetry").contains("trains=1");
        assertThat(trainManager.telemetries).hasSize(1);
        assertThat(trainManager.telemetries.get(0).trainNo()).isEqualTo(7);
    }

    @Test
    void treatsF1DatabaseNodeFrameAsFullFrameBeforeLifecyclePacket() {
        CapturingTrainManager trainManager = new CapturingTrainManager();
        SignalFrameDomainMapper mapper = new SignalFrameDomainMapper(trainManager);
        SignalDatabaseNodeFrame frame = new SignalDatabaseNodeFrame(
            SignalDatabaseNodeFrame.CAB_SWITCH_CONTENT,
            SignalDatabaseNodeFrameCodec.CENTRAL_SOURCE_ID,
            SignalDatabaseNodeFrameCodec.SIGNAL_SOURCE_ID,
            new byte[] {0x01, 0x02, 0x03}
        );

        SignalFrameDomainMapper.SignalInboundResult result = mapper.applyInbound(new SignalDatabaseNodeFrameCodec().encode(frame));

        assertThat(result.accepted()).isTrue();
        assertThat(result.summary()).contains("cab switch frame");
        assertThat(trainManager.applied).isNull();
    }

    private static class CapturingTrainManager extends TrainManager {

        private SignalTrainLifecycleCommand applied;
        private List<TrainOperationalTelemetry> telemetries = List.of();

        CapturingTrainManager() {
            super(null, null, null, null, null);
        }

        @Override
        public synchronized List<TrainState> applyLifecycleCommand(SignalTrainLifecycleCommand command) {
            this.applied = command;
            return new ArrayList<>();
        }

        @Override
        public synchronized List<TrainState> states() {
            return List.of();
        }

        @Override
        public synchronized void applyOperationalTelemetry(List<TrainOperationalTelemetry> telemetries) {
            this.telemetries = new ArrayList<>(telemetries);
        }
    }
}
