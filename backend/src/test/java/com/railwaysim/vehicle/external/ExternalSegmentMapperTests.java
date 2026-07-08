package com.railwaysim.vehicle.external;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.OperationalLineData.TrackSegmentDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExternalSegmentMapperTests {

    @Test
    void mapsPositionToSegmentOffsetAndProtectsBoundaries() {
        ExternalSegmentMapper mapper = new ExternalSegmentMapper(lineData(), Map.of("SEG-B", 902));

        assertThat(mapper.mapPosition(-5)).isEqualTo(new ExternalSegmentPosition(101, 0));
        assertThat(mapper.mapPosition(99.5)).isEqualTo(new ExternalSegmentPosition(101, 99.5));
        assertThat(mapper.mapPosition(100)).isEqualTo(new ExternalSegmentPosition(902, 0));
        assertThat(mapper.mapPosition(999)).isEqualTo(new ExternalSegmentPosition(902, 150));
    }

    private OperationalLineData lineData() {
        return new OperationalLineData(
            "1",
            "test-line",
            List.of(),
            List.of(
                segment("SEG-A", 101, 0, 100),
                segment("SEG-B", 102, 100, 250)
            ),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private TrackSegmentDefinition segment(String id, int rawSegmentId, double start, double end) {
        return new TrackSegmentDefinition(
            id,
            rawSegmentId,
            start,
            end,
            end - start,
            22.2,
            0,
            0,
            0,
            0,
            List.of(),
            List.of()
        );
    }
}
