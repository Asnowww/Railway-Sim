package com.railwaysim.dispatch;

import java.util.List;

public record DispatchSnapshot(
    String runMode,
    String planId,
    int targetHeadwaySeconds,
    int defaultDwellSeconds,
    boolean interventionActive,
    List<TrainProfileView> trainProfiles,
    List<DisturbanceView> openDisturbances,
    List<CommandView> activeCommands
) {
    public record TrainProfileView(
        String trainId,
        String frontTrainId,
        Double headwayActualSeconds,
        int headwayDeviationSeconds,
        String headwayState,
        String headwayAction,
        int dwellDeviationSeconds
    ) {
    }

    public record DisturbanceView(
        String id,
        String trainId,
        String stationId,
        String disturbanceType,
        double deviationValue,
        String status
    ) {
    }

    public record CommandView(
        String id,
        String trainId,
        String commandType,
        String status,
        String reason
    ) {
    }

    public static DispatchSnapshot empty() {
        return new DispatchSnapshot("FLAT", "", 300, 25, false, List.of(), List.of(), List.of());
    }
}
