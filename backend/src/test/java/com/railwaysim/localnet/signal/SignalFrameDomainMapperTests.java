package com.railwaysim.localnet.signal;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.signal.vehicle.SignalTrainLifecycleAction;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleCommand;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleCommandCodec;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleTrainSpec;
import com.railwaysim.train.TrainManager;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.external.ExternalTrainDirection;
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

    private static class CapturingTrainManager extends TrainManager {

        private SignalTrainLifecycleCommand applied;

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
    }
}
