package com.railwaysim.monitor;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FaultImpactCatalog {

    public FaultImpact resolve(Alarm alarm) {
        String code = alarm.id();
        String severity = alarm.level() >= 3 ? "CRITICAL" : alarm.level() == 2 ? "WARNING" : "INFO";
        List<String> trains = isVehicleSource(alarm.sourceModule())
            ? List.of(alarm.locationRef()) : List.of();
        List<String> sections = "power".equals(alarm.sourceModule())
            || "track".equals(alarm.sourceModule())
            ? List.of(alarm.locationRef()) : List.of();
        if (code.startsWith("SIGNAL_MA_LIMIT")) {
            trains = List.of(alarm.locationRef());
        }
        return new FaultImpact(
            severity, trains, sections, safetyAction(code), clearCondition(code),
            recoveryCondition(code));
    }

    private boolean isVehicleSource(String source) {
        return "vehicle".equals(source) || "train".equals(source);
    }

    private String safetyAction(String code) {
        if (code.startsWith("SIGNAL_MA_LIMIT")) return "TRACTION_CUTOFF_AND_SAFE_BRAKE";
        if (code.startsWith("TRACK_FAULT")) return "STOP_BEFORE_FAULTED_SEGMENT_AND_SET_SIGNAL_RED";
        if (code.startsWith("POWER_STATE") || code.startsWith("POWER_FAULT")) {
            return "POWER_DERATE_OR_TRACTION_CUTOFF";
        }
        if (code.startsWith("POWER_LOCK")) return "INHIBIT_ENERGIZATION";
        if (code.startsWith("TRAIN_FAULT")) return "APPLY_VEHICLE_PROTECTION";
        if (code.startsWith("FMU_STEP_FAILED") || code.startsWith("FMU_FALLBACK")) {
            return "ENTER_STICKY_FALLBACK";
        }
        return "MONITOR";
    }

    private String clearCondition(String code) {
        if (code.startsWith("POWER_LOCK")) return "MAINTENANCE_LOCK_NONE";
        if (code.startsWith("POWER_STATE") || code.startsWith("POWER_FAULT")) {
            return "POWER_SECTION_ENERGIZED_AND_PROTECTION_RESET";
        }
        if (code.startsWith("TRAIN_FAULT")) return "VEHICLE_FAULT_CLEARED_AND_SELF_CHECK_PASS";
        if (code.startsWith("SIGNAL_MA_LIMIT")) return "VALID_MOVEMENT_AUTHORITY_AVAILABLE";
        if (code.startsWith("TRACK_FAULT")) return "TRACK_SEGMENT_FAULT_CLEARED";
        return "SOURCE_CONDITION_CLEARED";
    }

    private String recoveryCondition(String code) {
        if (code.startsWith("FMU_")) return "EXPLICIT_RESYNC_AND_VERSION_TICK_MATCH";
        if (code.startsWith("POWER_")) return "RUN_TICK_TOPOLOGY_CONFIG_MATCH";
        if (code.startsWith("TRAIN_FAULT")) return "SELF_CHECK_PASS_AND_CONTROL_STATE_RECONCILED";
        if (code.startsWith("TRACK_FAULT")) return "OCCUPANCY_RECALCULATED_AND_MOVEMENT_AUTHORITY_REISSUED";
        return "STATE_RECONCILED";
    }
}
