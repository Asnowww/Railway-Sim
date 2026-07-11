package com.railwaysim.dispatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "railway.dispatch")
public class DispatchProperties {

    private String planLocation = "classpath:config/dispatch-plan.yaml";
    private double averageSpeedRatio = 0.8;
    private int dwellToleranceSec = 15;
    private int departureDelaySec = 30;
    private double headwayShrinkRatio = 0.7;
    private double headwayExpandRatio = 1.5;
    private double crowdingLoadRate = 0.8;
    private int confirmTicks = 3;
    private int recoverTicks = 5;
    private double recoverRatio = 0.3;
    private int cooldownSec = 60;
    private long evaluateIntervalMs = 1000;
    private double arrivalThresholdMeters = 5.0;
    private double stopSpeedThresholdMps = 0.5;
    private double baseCruiseSpeedMps = 15.0;
    private int minDwellSec = 15;
    private int maxDwellSec = 60;
    private int commandEffectTimeoutSec = 180;
    private boolean routeDispatchEnabled = true;
    private double routeApproachWindowMeters = 600.0;
    private int routeApproachLookaheadSeconds = 45;
    private int routeIntentValiditySeconds = 90;
    private int routeRequestCooldownSeconds = 20;

    public String getPlanLocation() {
        return planLocation;
    }

    public void setPlanLocation(String planLocation) {
        this.planLocation = planLocation;
    }

    public double getAverageSpeedRatio() {
        return averageSpeedRatio;
    }

    public void setAverageSpeedRatio(double averageSpeedRatio) {
        this.averageSpeedRatio = averageSpeedRatio;
    }

    public int getDwellToleranceSec() {
        return dwellToleranceSec;
    }

    public void setDwellToleranceSec(int dwellToleranceSec) {
        this.dwellToleranceSec = dwellToleranceSec;
    }

    public int getDepartureDelaySec() {
        return departureDelaySec;
    }

    public void setDepartureDelaySec(int departureDelaySec) {
        this.departureDelaySec = departureDelaySec;
    }

    public double getHeadwayShrinkRatio() {
        return headwayShrinkRatio;
    }

    public void setHeadwayShrinkRatio(double headwayShrinkRatio) {
        this.headwayShrinkRatio = headwayShrinkRatio;
    }

    public double getHeadwayExpandRatio() {
        return headwayExpandRatio;
    }

    public void setHeadwayExpandRatio(double headwayExpandRatio) {
        this.headwayExpandRatio = headwayExpandRatio;
    }

    public double getCrowdingLoadRate() {
        return crowdingLoadRate;
    }

    public void setCrowdingLoadRate(double crowdingLoadRate) {
        this.crowdingLoadRate = crowdingLoadRate;
    }

    public int getConfirmTicks() {
        return confirmTicks;
    }

    public void setConfirmTicks(int confirmTicks) {
        this.confirmTicks = confirmTicks;
    }

    public int getRecoverTicks() {
        return recoverTicks;
    }

    public void setRecoverTicks(int recoverTicks) {
        this.recoverTicks = recoverTicks;
    }

    public double getRecoverRatio() {
        return recoverRatio;
    }

    public void setRecoverRatio(double recoverRatio) {
        this.recoverRatio = recoverRatio;
    }

    public int getCooldownSec() {
        return cooldownSec;
    }

    public void setCooldownSec(int cooldownSec) {
        this.cooldownSec = cooldownSec;
    }

    public long getEvaluateIntervalMs() {
        return evaluateIntervalMs;
    }

    public void setEvaluateIntervalMs(long evaluateIntervalMs) {
        this.evaluateIntervalMs = evaluateIntervalMs;
    }

    public double getArrivalThresholdMeters() {
        return arrivalThresholdMeters;
    }

    public void setArrivalThresholdMeters(double arrivalThresholdMeters) {
        this.arrivalThresholdMeters = arrivalThresholdMeters;
    }

    public double getStopSpeedThresholdMps() {
        return stopSpeedThresholdMps;
    }

    public void setStopSpeedThresholdMps(double stopSpeedThresholdMps) {
        this.stopSpeedThresholdMps = stopSpeedThresholdMps;
    }

    public double getBaseCruiseSpeedMps() {
        return baseCruiseSpeedMps;
    }

    public void setBaseCruiseSpeedMps(double baseCruiseSpeedMps) {
        this.baseCruiseSpeedMps = baseCruiseSpeedMps;
    }

    public int getMinDwellSec() {
        return minDwellSec;
    }

    public void setMinDwellSec(int minDwellSec) {
        this.minDwellSec = minDwellSec;
    }

    public int getMaxDwellSec() {
        return maxDwellSec;
    }

    public void setMaxDwellSec(int maxDwellSec) {
        this.maxDwellSec = maxDwellSec;
    }

    public int getCommandEffectTimeoutSec() {
        return commandEffectTimeoutSec;
    }

    public void setCommandEffectTimeoutSec(int commandEffectTimeoutSec) {
        this.commandEffectTimeoutSec = commandEffectTimeoutSec;
    }

    public boolean isRouteDispatchEnabled() {
        return routeDispatchEnabled;
    }

    public void setRouteDispatchEnabled(boolean routeDispatchEnabled) {
        this.routeDispatchEnabled = routeDispatchEnabled;
    }

    public double getRouteApproachWindowMeters() {
        return routeApproachWindowMeters;
    }

    public void setRouteApproachWindowMeters(double routeApproachWindowMeters) {
        this.routeApproachWindowMeters = routeApproachWindowMeters;
    }

    public int getRouteApproachLookaheadSeconds() {
        return routeApproachLookaheadSeconds;
    }

    public void setRouteApproachLookaheadSeconds(int routeApproachLookaheadSeconds) {
        this.routeApproachLookaheadSeconds = routeApproachLookaheadSeconds;
    }

    public int getRouteIntentValiditySeconds() {
        return routeIntentValiditySeconds;
    }

    public void setRouteIntentValiditySeconds(int routeIntentValiditySeconds) {
        this.routeIntentValiditySeconds = routeIntentValiditySeconds;
    }

    public int getRouteRequestCooldownSeconds() {
        return routeRequestCooldownSeconds;
    }

    public void setRouteRequestCooldownSeconds(int routeRequestCooldownSeconds) {
        this.routeRequestCooldownSeconds = routeRequestCooldownSeconds;
    }
}
