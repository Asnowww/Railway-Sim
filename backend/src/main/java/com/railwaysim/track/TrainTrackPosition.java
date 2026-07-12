package com.railwaysim.track;

import java.time.Instant;

/**
 * 列车拓扑位置模型（WP-02）。
 *
 * <p>相比只依赖全局 {@code positionMeters}，本记录增加 {@code segmentId + offsetMeters + direction}，
 * 使轨道安全计算在平行支路、反向列车和道岔区域能够精确定位。
 *
 * @param trainId        列车标识
 * @param segmentId      当前所在区段标识（如 "T01"）
 * @param offsetMeters   距区段起点的偏移（米），不应为负或超过区段长度
 * @param direction      运行方向：UP / DOWN / UNKNOWN
 * @param headMileage    车头全局公里标（保留用于直线兼容和前端展示）
 * @param tailMileage    车尾全局公里标
 * @param sourceTimestamp 位置数据源时间戳
 * @param dataQuality    数据质量
 */
public record TrainTrackPosition(
    String trainId,
    String segmentId,
    double offsetMeters,
    String direction,
    double headMileage,
    double tailMileage,
    Instant sourceTimestamp,
    String dataQuality
) {
    public TrainTrackPosition {
        offsetMeters = Math.max(0, offsetMeters);
        direction = (direction == null || direction.isBlank()) ? "UNKNOWN" : direction;
        dataQuality = (dataQuality == null || dataQuality.isBlank()) ? "GOOD" : dataQuality;
    }
}
