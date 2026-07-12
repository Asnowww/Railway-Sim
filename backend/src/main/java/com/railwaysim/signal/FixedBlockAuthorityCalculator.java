package com.railwaysim.signal;

import com.railwaysim.dispatch.DispatchConstraint;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.track.TrackOccupancy;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.track.TrackService;
import com.railwaysim.train.TrainState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 固定闭塞授权计算器（WP-04）。
 *
 * <p>策略：前方区段被占用或故障时，本车 MA 不能越过该区段起点。
 * 同一区段最多只允许一列车占用（区段即"闭塞分区"）。
 */
@Component
public class FixedBlockAuthorityCalculator implements AuthorityCalculator {

    private static final double DEFAULT_BRAKING = 0.8;
    private final TrackService trackService;

    public FixedBlockAuthorityCalculator(TrackService trackService) {
        this.trackService = trackService;
    }

    @Override
    public AuthorityResult calculate(
        List<TrainState> trains,
        Map<String, TrackConstraint> trackByTrain,
        Map<String, DispatchConstraint> dispatchByTrain,
        double lineLengthMeters,
        double safetyGap
    ) {
        List<TrainState> ordered = new ArrayList<>(trains);
        ordered.sort(Comparator.comparingDouble(TrainState::positionMeters));

        List<MovementAuthority> authorities = new ArrayList<>();
        Set<String> reserved = new HashSet<>();
        Map<String, TrackSegmentState> segs = new LinkedHashMap<>();
        Set<String> blockedStartPoints = new HashSet<>();

        // 从后向前标记受阻点
        for (int i = ordered.size() - 1; i >= 0; i--) {
            TrainState train = ordered.get(i);
            TrackSegmentState mySeg = trackService.segmentAt(train.positionMeters());
            if (mySeg == null) {
                authorities.add(new MovementAuthority(train.id(), train.positionMeters(), 0, "NO_SEGMENT", "?", "?", "NO_SEGMENT"));
                continue;
            }
            segs.put(train.id(), mySeg);

            double limit = lineLengthMeters;
            String reason = "前方区段空闲";
            String reasonCode = "NORMAL";

            // 前车占用点
            if (i + 1 < ordered.size()) {
                TrainState next = ordered.get(i + 1);
                TrackSegmentState nextSeg = segs.get(next.id());
                limit = nextSeg != null ? nextSeg.startMeters() : next.positionMeters();
                reason = "前车限速";
                reasonCode = "TRAIN_AHEAD";
            }
            // 自己所在区段如果已经有别的车（不可能，但防御式写法）
            // 故障区段
            double faultPos = trackService.nextFaultPosition(train.positionMeters());
            if (faultPos < limit && faultPos < Double.POSITIVE_INFINITY) {
                limit = faultPos - safetyGap;
                reason = "故障降级";
                reasonCode = "FAULT_LIMIT";
            }

            double authorityEnd = Math.max(train.positionMeters(), Math.min(limit, lineLengthMeters));
            if (authorityEnd <= train.positionMeters()) {
                authorityEnd = train.positionMeters();
                reason = "前方安全距离不足";
                reasonCode = "BLOCKED";
            }

            double maDist = Math.max(0, authorityEnd - train.positionMeters());
            double safeSpeed = Math.sqrt(2 * DEFAULT_BRAKING * maDist);
            TrackConstraint tc = trackByTrain.get(train.id());
            double segSpeed = tc != null ? tc.speedLimitMetersPerSecond() : 22.2;
            DispatchConstraint dc = dispatchByTrain.get(train.id());
            double dcSpeed = dc != null ? dc.applyToSpeedLimit(safeSpeed) : safeSpeed;
            double speed = Math.min(segSpeed, dcSpeed);

            String endSegId = trackService.segmentAt(authorityEnd) != null
                ? trackService.segmentAt(authorityEnd).id() : mySeg.id();

            authorities.add(new MovementAuthority(train.id(), authorityEnd, speed, reason, mySeg.id(), endSegId, reasonCode));

            // 预留：本车占用的区段不预留给前车
            TrackSegmentState occSeg = trackService.segmentAt(train.positionMeters());
            if (occSeg != null) blockedStartPoints.add(String.valueOf(occSeg.startMeters()));
        }

        // RESERVED: 前 N 个不在已占用区段的区段
        for (MovementAuthority ma : authorities) {
            TrackSegmentState seg = trackService.segmentAt(ma.authorityEndMeters());
            if (seg != null && !blockedStartPoints.contains(String.valueOf(seg.startMeters()))) {
                reserved.add(seg.id());
            }
        }

        return new AuthorityResult(authorities, reserved, segs);
    }
}
