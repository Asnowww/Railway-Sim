package com.railwaysim.vehicle.external;

import java.util.LinkedHashMap;
import java.util.Map;

public class RtLabVariablePathMapper {

    private static final String ROOT = "PowerSystemAndTrainsV1";

    public RtLabTrainInputPaths inputPaths(int trainNo) {
        validateTrainNo(trainNo);
        String controlBase = groupBase(trainNo) + "/Train_Control";
        return new RtLabTrainInputPaths(
            trainNo,
            controlBase + "/ID" + trainNo + "/Value",
            controlBase + "/num_seg" + trainNo + "/Value",
            controlBase + "/x0_seg" + trainNo + "/Value",
            controlBase + "/sig_train" + trainNo + "/Value",
            controlBase + "/handle" + trainNo + "/Value",
            controlBase + "/active_Tc" + trainNo + "/Value"
        );
    }

    public RtLabTrainOutputPaths outputPaths(int trainNo) {
        validateTrainNo(trainNo);
        String outputBase = groupBase(trainNo) + "/Train_Output";
        return new RtLabTrainOutputPaths(
            trainNo,
            outputBase + "/ID" + trainNo + "/Value",
            outputBase + "/active_Tc" + trainNo + "/Value",
            outputBase + "/sig_train" + trainNo + "/Value",
            outputBase + "/acc" + trainNo + "/Value",
            outputBase + "/vel" + trainNo + "/Value",
            outputBase + "/x" + trainNo + "/Value"
        );
    }

    public Map<String, Double> inputParameterValues(ExternalTrainCommand command) {
        RtLabTrainInputPaths paths = inputPaths(command.trainNo());
        Map<String, Double> values = new LinkedHashMap<>();
        values.put(paths.trainId(), (double) command.trainNo());
        values.put(paths.segNo(), (double) command.segNo());
        values.put(paths.offset(), command.offset());
        values.put(paths.direction(), (double) command.direction().protocolCode());
        values.put(paths.handle(), (double) command.command());
        values.put(paths.activeCab(), (double) command.activeCab());
        return values;
    }

    private String groupBase(int trainNo) {
        int start;
        int end;
        if (trainNo <= 2) {
            start = 1;
            end = 2;
        } else {
            start = 3 + ((trainNo - 3) / 3) * 3;
            end = Math.min(20, start + 2);
        }
        return ROOT + "/SS_Trains" + start + "_" + end + "/Train" + start + "_" + end;
    }

    private void validateTrainNo(int trainNo) {
        if (trainNo < 1 || trainNo > 20) {
            throw new IllegalArgumentException("RT-LAB train number must be in 1..20");
        }
    }
}
