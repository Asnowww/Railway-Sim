package com.railwaysim.track;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TrackService {

    public List<TrackSegmentState> states() {
        return List.of(
            new TrackSegmentState("T01", 0, 1250, 20, TrackOccupancy.FREE),
            new TrackSegmentState("T02", 1250, 2500, 22.2, TrackOccupancy.FREE),
            new TrackSegmentState("T03", 2500, 3750, 22.2, TrackOccupancy.FREE),
            new TrackSegmentState("T04", 3750, 5000, 20, TrackOccupancy.FREE)
        );
    }
}

