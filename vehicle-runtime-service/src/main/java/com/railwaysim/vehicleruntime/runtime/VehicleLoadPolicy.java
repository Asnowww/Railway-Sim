package com.railwaysim.vehicleruntime.runtime;

/**
 * 外部运行时复制中央车辆载荷规则，保证控制队列和本地 fallback 行为接近。
 */
final class VehicleLoadPolicy {

    static final double EMPTY_MASS_KG = 180_000;
    static final double CRUSH_LOAD_MASS_KG = 70_000;
    static final int NOMINAL_TRACTION_UNITS = 4;
    static final int NOMINAL_BRAKE_UNITS = 4;

    private VehicleLoadPolicy() {
    }

    static double loadMassKg(double explicitLoadMassKg, double loadRate) {
        return explicitLoadMassKg > 0 ? explicitLoadMassKg : loadMassFromRate(loadRate);
    }

    static double loadMassFromRate(double loadRate) {
        return CRUSH_LOAD_MASS_KG * clamp(loadRate, 0, 1.3);
    }

    static double totalMassKg(double loadMassKg) {
        return EMPTY_MASS_KG + Math.max(0, loadMassKg);
    }

    static String overloadStatus(double loadMassKg) {
        double rate = CRUSH_LOAD_MASS_KG <= 0 ? 0 : loadMassKg / CRUSH_LOAD_MASS_KG;
        if (rate > 1.15) {
            return "CRUSH_OVERLOAD";
        }
        if (rate > 1.0) {
            return "OVERLOAD";
        }
        return "NORMAL";
    }

    static boolean overloaded(String overloadStatus) {
        return "OVERLOAD".equals(overloadStatus) || "CRUSH_OVERLOAD".equals(overloadStatus);
    }

    static double tractionCommandFactor(double loadMassKg, int availableTractionCount) {
        double unitFactor = normalizeUnitCount(availableTractionCount, NOMINAL_TRACTION_UNITS) / (double) NOMINAL_TRACTION_UNITS;
        String overloadStatus = overloadStatus(loadMassKg);
        double loadFactor = "CRUSH_OVERLOAD".equals(overloadStatus) ? 0.55 : "OVERLOAD".equals(overloadStatus) ? 0.75 : 1.0;
        return clamp(unitFactor * loadFactor, 0, 1);
    }

    static double brakingDecelerationFactor(double loadMassKg, int availableBrakeCount) {
        double unitFactor = normalizeUnitCount(availableBrakeCount, NOMINAL_BRAKE_UNITS) / (double) NOMINAL_BRAKE_UNITS;
        double loadPenalty = clamp(1.0 - Math.max(0, loadMassKg - CRUSH_LOAD_MASS_KG) / CRUSH_LOAD_MASS_KG * 0.25, 0.65, 1.0);
        return clamp(unitFactor * loadPenalty, 0.2, 1.2);
    }

    static int normalizeUnitCount(int value, int defaults) {
        return value <= 0 ? defaults : Math.min(value, defaults);
    }

    static String vehicleProtectionReason(String overloadStatus) {
        return overloaded(overloadStatus) ? "OVERLOAD_TRACTION_LIMIT" : "NONE";
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
