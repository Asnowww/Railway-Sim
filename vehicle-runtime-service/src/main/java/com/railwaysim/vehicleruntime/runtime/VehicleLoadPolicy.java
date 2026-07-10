package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleParameters;

/** Vehicle load and equipment derating rules backed by the validated YAML parameter set. */
final class VehicleLoadPolicy {

    static final int NOMINAL_TRACTION_UNITS = 4;
    static final int NOMINAL_BRAKE_UNITS = 4;

    private final VehicleParameters parameters;

    VehicleLoadPolicy(VehicleParameters parameters) {
        this.parameters = parameters;
    }

    double loadMassKg(double explicitLoadMassKg, double loadRate) {
        return explicitLoadMassKg > 0 ? explicitLoadMassKg : loadMassFromRate(loadRate);
    }

    double loadMassFromRate(double loadRate) {
        return parameters.maxLoadMassKg() * clamp(loadRate, 0, 1.3);
    }

    double totalMassKg(double loadMassKg) {
        return parameters.emptyMassKg() + Math.max(0, loadMassKg);
    }

    String overloadStatus(double loadMassKg) {
        double rate = parameters.maxLoadMassKg() <= 0 ? 0 : loadMassKg / parameters.maxLoadMassKg();
        if (rate > 1.15) {
            return "CRUSH_OVERLOAD";
        }
        if (rate > 1.0) {
            return "OVERLOAD";
        }
        return "NORMAL";
    }

    boolean overloaded(String overloadStatus) {
        return "OVERLOAD".equals(overloadStatus) || "CRUSH_OVERLOAD".equals(overloadStatus);
    }

    double tractionCommandFactor(double loadMassKg, int availableTractionCount) {
        double unitFactor = normalizeUnitCount(availableTractionCount, NOMINAL_TRACTION_UNITS) / (double) NOMINAL_TRACTION_UNITS;
        String overloadStatus = overloadStatus(loadMassKg);
        double loadFactor = "CRUSH_OVERLOAD".equals(overloadStatus) ? 0.55 : "OVERLOAD".equals(overloadStatus) ? 0.75 : 1.0;
        return clamp(unitFactor * loadFactor, 0, 1);
    }

    double brakingDecelerationFactor(double loadMassKg, int availableBrakeCount) {
        double unitFactor = normalizeUnitCount(availableBrakeCount, NOMINAL_BRAKE_UNITS) / (double) NOMINAL_BRAKE_UNITS;
        double loadPenalty = clamp(
            1.0 - Math.max(0, loadMassKg - parameters.maxLoadMassKg()) / parameters.maxLoadMassKg() * 0.25,
            0.65,
            1.0
        );
        return clamp(unitFactor * loadPenalty, 0.2, 1.2);
    }

    int normalizeUnitCount(int value, int defaults) {
        return value <= 0 ? defaults : Math.min(value, defaults);
    }

    String vehicleProtectionReason(String overloadStatus) {
        return overloaded(overloadStatus) ? "OVERLOAD_TRACTION_LIMIT" : "NONE";
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
