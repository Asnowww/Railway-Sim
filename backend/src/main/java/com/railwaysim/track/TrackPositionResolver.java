package com.railwaysim.track;

import com.railwaysim.train.TrainState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 将 {@link TrainState} 位置的全局 {@code positionMeters} 转换为
 * 拓扑感知的 {@link TrainTrackPosition}（WP-02）。
 */
@Component
public class TrackPositionResolver {

    private final TrackService trackService;

    public TrackPositionResolver(TrackService trackService) {
        this.trackService = trackService;
    }

    public TrainTrackPosition resolve(TrainState train) {
        TrackSegmentState seg = trackService.segmentAt(train.positionMeters());
        String segId = seg != null ? seg.id() : "?";
        double offset = seg != null
            ? Math.max(0, Math.min(seg.endMeters() - seg.startMeters(), train.positionMeters() - seg.startMeters()))
            : train.positionMeters();
        String dir = train.direction() != null ? train.direction() : "UNKNOWN";
        double head = train.headMileage() > 0 ? train.headMileage() : train.positionMeters();
        double tail = train.tailMileage() > 0 ? train.tailMileage() : train.positionMeters() - train.lengthMeters();

        return new TrainTrackPosition(
            train.id(),
            segId,
            offset,
            dir,
            head,
            tail,
            Instant.now(),
            "GOOD"
        );
    }

    public List<TrainTrackPosition> resolveAll(List<TrainState> trains) {
        List<TrainTrackPosition> result = new ArrayList<>();
        for (TrainState t : trains) result.add(resolve(t));
        return result;
    }
}
