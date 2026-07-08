package com.railwaysim.vehicle;

public final class VehicleLoadPolicy {

    public static final double EMPTY_MASS_KG = 198_000;
    public static final double MAX_LOAD_MASS_KG = 72_000;
    public static final int NOMINAL_TRACTION_UNITS = 6;
    public static final int NOMINAL_BRAKE_UNITS = 6;

    private VehicleLoadPolicy() {
    }

    public static double loadMassKg(double protocolLoadMassKg, double loadRate) {
        if (Double.isFinite(protocolLoadMassKg) && protocolLoadMassKg >= 0) {
            return protocolLoadMassKg;
        }
        return loadMassFromRate(loadRate);
    }

    public static double loadMassFromRate(double loadRate) {
        return Math.max(0, loadRate) * MAX_LOAD_MASS_KG;
    }

    public static double loadRateFromMass(double loadMassKg) {
        if (!Double.isFinite(loadMassKg) || loadMassKg <= 0) {
            return 0;
        }
        return loadMassKg / MAX_LOAD_MASS_KG;
    }

    public static String overloadStatus(double loadMassKg) {
        double rate = loadRateFromMass(loadMassKg);
        if (rate > 1.15) {
            return "CRITICAL_OVERLOAD";
        }
        if (rate > 1.0) {
            return "OVERLOAD";
        }
        if (rate >= 0.9) {
            return "NEAR_CAPACITY";
        }
        return "NORMAL";
    }

    public static boolean overloaded(String overloadStatus) {
        return "OVERLOAD".equals(overloadStatus) || "CRITICAL_OVERLOAD".equals(overloadStatus);
    }

    public static String vehicleProtectionReason(String overloadStatus) {
        if ("CRITICAL_OVERLOAD".equals(overloadStatus)) {
            return "CRITICAL_OVERLOAD";
        }
        if ("OVERLOAD".equals(overloadStatus)) {
            return "OVERLOAD";
        }
        return "NONE";
    }

    public static double totalMassKg(double loadMassKg) {
        return EMPTY_MASS_KG + Math.max(0, loadMassKg);
    }

    public static double tractionCommandFactor(double loadMassKg, int availableTractionCount) {
        double unitFactor = clamp(availableTractionCount / (double) NOMINAL_TRACTION_UNITS, 0, 1);
        return unitFactor * overloadTractionFactor(overloadStatus(loadMassKg));
    }

    public static double brakingDecelerationFactor(double loadMassKg, int availableBrakeCount) {
        double unitFactor = clamp(availableBrakeCount / (double) NOMINAL_BRAKE_UNITS, 0, 1);
        double loadFactor = clamp(1.0 / Math.max(1.0, loadRateFromMass(loadMassKg)), 0.72, 1.0);
        return unitFactor * loadFactor;
    }

    public static int normalizeUnitCount(int count, int nominalCount) {
        return Math.max(0, Math.min(nominalCount, count));
    }

    private static double overloadTractionFactor(String overloadStatus) {
        return switch (overloadStatus) {
            case "CRITICAL_OVERLOAD" -> 0.55;
            case "OVERLOAD" -> 0.75;
            case "NEAR_CAPACITY" -> 0.90;
            default -> 1.0;
        };
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
