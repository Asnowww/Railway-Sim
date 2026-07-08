package com.railwaysim.vehicle.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RtLabVariablePathMapperTests {

    @Test
    void expandsProtocolInputPathsForTrainOneAndTrainTwenty() {
        RtLabVariablePathMapper mapper = new RtLabVariablePathMapper();

        RtLabTrainInputPaths train1 = mapper.inputPaths(1);
        RtLabTrainInputPaths train20 = mapper.inputPaths(20);

        assertThat(train1.trainId())
            .isEqualTo("PowerSystemAndTrainsV1/SS_Trains1_2/Train1_2/Train_Control/ID1/Value");
        assertThat(train1.segNo())
            .isEqualTo("PowerSystemAndTrainsV1/SS_Trains1_2/Train1_2/Train_Control/num_seg1/Value");
        assertThat(train20.trainId())
            .isEqualTo("PowerSystemAndTrainsV1/SS_Trains18_20/Train18_20/Train_Control/ID20/Value");
        assertThat(train20.activeCab())
            .isEqualTo("PowerSystemAndTrainsV1/SS_Trains18_20/Train18_20/Train_Control/active_Tc20/Value");
    }

    @Test
    void convertsExternalCommandToSixRtLabInputParameters() {
        RtLabVariablePathMapper mapper = new RtLabVariablePathMapper();
        ExternalTrainCommand command = new ExternalTrainCommand(
            20,
            2,
            100,
            88,
            12.5,
            ExternalTrainDirection.DOWN,
            2
        );

        Map<String, Double> values = mapper.inputParameterValues(command);

        assertThat(values).hasSize(6);
        assertThat(values.get("PowerSystemAndTrainsV1/SS_Trains18_20/Train18_20/Train_Control/ID20/Value"))
            .isEqualTo(20.0);
        assertThat(values.get("PowerSystemAndTrainsV1/SS_Trains18_20/Train18_20/Train_Control/num_seg20/Value"))
            .isEqualTo(88.0);
        assertThat(values.get("PowerSystemAndTrainsV1/SS_Trains18_20/Train18_20/Train_Control/x0_seg20/Value"))
            .isEqualTo(12.5);
        assertThat(values.get("PowerSystemAndTrainsV1/SS_Trains18_20/Train18_20/Train_Control/sig_train20/Value"))
            .isEqualTo(170.0);
        assertThat(values.get("PowerSystemAndTrainsV1/SS_Trains18_20/Train18_20/Train_Control/handle20/Value"))
            .isEqualTo(2.0);
        assertThat(values.get("PowerSystemAndTrainsV1/SS_Trains18_20/Train18_20/Train_Control/active_Tc20/Value"))
            .isEqualTo(2.0);
    }
}
