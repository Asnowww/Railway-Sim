package com.railwaysim.track;

import com.railwaysim.train.TrainState;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TrackService {

    private final List<TrackSegmentState> segments = new ArrayList<>(List.of(
        new TrackSegmentState("T01", 0, 1250, 20, TrackOccupancy.FREE),
        new TrackSegmentState("T02", 1250, 2500, 22.2, TrackOccupancy.FREE),
        new TrackSegmentState("T03", 2500, 3750, 22.2, TrackOccupancy.FREE),
        new TrackSegmentState("T04", 3750, 5000, 20, TrackOccupancy.FREE)
    ));

    public synchronized void updateOccupancy(List<TrainState> trains) {
        for (int i = 0; i < segments.size(); i++) {
            TrackSegmentState segment = segments.get(i);
            segments.set(i, new TrackSegmentState(
                segment.id(),
                segment.startMeters(),
                segment.endMeters(),
                segment.speedLimitMetersPerSecond(),
                TrackOccupancy.FREE
            ));
        }
        for (TrainState train : trains) {
            double tail = train.positionMeters() - train.lengthMeters();
            double head = train.positionMeters();
            for (int i = 0; i < segments.size(); i++) {
                TrackSegmentState segment = segments.get(i);
                if (overlaps(tail, head, segment.startMeters(), segment.endMeters())) {
                    segments.set(i, new TrackSegmentState(
                        segment.id(),
                        segment.startMeters(),
                        segment.endMeters(),
                        segment.speedLimitMetersPerSecond(),
                        TrackOccupancy.OCCUPIED
                    ));
                }
            }
        }
    }

    public synchronized List<TrackSegmentState> states() {
        return List.copyOf(segments);
    }

    private boolean overlaps(double startA, double endA, double startB, double endB) {
        return endA >= startB && startA <= endB;
    }
}
