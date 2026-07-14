package com.railwaysim.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 停车点解析：车头停对应股道站台的 stop_right，上下行里程重叠时按 track 区分。 */
class OperationalLineDataStopPointTests {

    private static final double UP_STOP_RIGHT = 1783.14;
    private static final double DOWN_STOP_RIGHT = 1778.52;
    private static final double CENTER = 1724.14;

    @Test
    void stopPointUsesPlatformStopRightForMatchingTrack() {
        OperationalLineData lineData = lineDataWithPlatforms();
        OperationalLineData.StationDefinition station = lineData.stations().get(0);

        assertThat(lineData.stopPointMeters(station, "up")).isEqualTo(UP_STOP_RIGHT);
        assertThat(lineData.stopPointMeters(station, "down")).isEqualTo(DOWN_STOP_RIGHT);
    }

    @Test
    void stopPointFallsBackToStationCenterWithoutPlatforms() {
        OperationalLineData lineData = lineDataWithoutPlatforms();
        OperationalLineData.StationDefinition station = lineData.stations().get(0);

        assertThat(lineData.stopPointMeters(station, "up")).isEqualTo(CENTER);
    }

    @Test
    void trackAwareControlDistanceIsSignedInsideStopWindow() {
        OperationalLineData lineData = lineDataWithPlatforms();

        // 接近停车点：正距离
        assertThat(lineData.stationControlDistanceMeters(UP_STOP_RIGHT - 3, 10, "up"))
            .isCloseTo(3, within(1e-9));
        // 越过停车点：负距离（供车辆层判定已对位/越过）
        assertThat(lineData.stationControlDistanceMeters(UP_STOP_RIGHT + 2, 10, "up"))
            .isCloseTo(-2, within(1e-9));
        // 窗口外：到前方停车点的距离
        assertThat(lineData.stationControlDistanceMeters(1000, 10, "up"))
            .isCloseTo(UP_STOP_RIGHT - 1000, within(1e-9));
    }

    private static OperationalLineData lineDataWithPlatforms() {
        return lineData(
            List.of(new OperationalLineData.StationDefinition(
                "S102", "丰台科技园站", CENTER, List.of("S102-UP", "S102-DOWN"))),
            List.of(
                new OperationalLineData.PlatformDefinition(
                    "S102-UP", CENTER, "U20", "UP", null, null,
                    UP_STOP_RIGHT - 118, UP_STOP_RIGHT, "right"),
                new OperationalLineData.PlatformDefinition(
                    "S102-DOWN", 1719.52, "D17", "DOWN", null, null,
                    DOWN_STOP_RIGHT - 118, DOWN_STOP_RIGHT, "left")
            )
        );
    }

    private static OperationalLineData lineDataWithoutPlatforms() {
        return lineData(
            List.of(new OperationalLineData.StationDefinition("S102", "丰台科技园站", CENTER, List.of())),
            List.of()
        );
    }

    private static OperationalLineData lineData(
        List<OperationalLineData.StationDefinition> stations,
        List<OperationalLineData.PlatformDefinition> platforms
    ) {
        return new OperationalLineData(
            "m9-test",
            "M9 Test",
            List.of(),
            List.of(new OperationalLineData.TrackSegmentDefinition(
                "U20", 20, 0, 5000, 5000, 22.2, 0, 0, 0, 0,
                List.of(), List.of(), "NU5", "NU6", "up")),
            List.of(),
            List.of(),
            List.of(),
            stations,
            platforms,
            List.of(),
            List.of(),
            List.of()
        );
    }
}
