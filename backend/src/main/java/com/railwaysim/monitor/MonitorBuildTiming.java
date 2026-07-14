package com.railwaysim.monitor;

/** Internal cost of producing one central monitoring snapshot. */
public record MonitorBuildTiming(
    double serviceHealthMillis,
    double alarmProjectionMillis,
    double alarmReconciliationMillis,
    double totalMillis
) {
    public static MonitorBuildTiming idle() {
        return new MonitorBuildTiming(0, 0, 0, 0);
    }
}
