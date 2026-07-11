package com.railwaysim.signal;

import com.railwaysim.dispatch.DispatchConstraint;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.train.TrainState;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 移动授权计算策略接口（WP-04）。
 *
 * <p>实现类负责按阻塞模式计算每列车的 MA 终点、限速和 RESERVED 区段。
 */
public interface AuthorityCalculator {

    /**
     * @param trains              全量列车列表
     * @param trackByTrain        每列车轨道约束
     * @param dispatchByTrain     每列车调度约束
     * @param lineLengthMeters    线路总长
     * @param safetyGap           基础安全距离
     * @return 计算结果：authorities + allReserved + currentSegments
     */
    AuthorityResult calculate(
        List<TrainState> trains,
        Map<String, TrackConstraint> trackByTrain,
        Map<String, DispatchConstraint> dispatchByTrain,
        double lineLengthMeters,
        double safetyGap
    );

    record AuthorityResult(
        List<MovementAuthority> authorities,
        Set<String> reservedSegmentIds,
        Map<String, TrackSegmentState> currentSegments
    ) {}
}
