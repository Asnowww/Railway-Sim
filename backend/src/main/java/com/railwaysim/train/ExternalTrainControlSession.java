package com.railwaysim.train;

import com.railwaysim.power.PowerConstraint;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.vehicle.external.ExternalTrainDirection;

public class ExternalTrainControlSession {

    private final String trainId;
    private final int linkId;
    private final double attachOffsetMeters;
    private final ExternalTrainDirection direction;
    private ExternalTrainControlSessionState state;
    private String signalNetworkStatus;
    private String powerNetworkStatus;
    private String reason;

    private ExternalTrainControlSession(
        String trainId,
        int linkId,
        double attachOffsetMeters,
        ExternalTrainDirection direction,
        ExternalTrainControlSessionState state,
        String signalNetworkStatus,
        String powerNetworkStatus,
        String reason
    ) {
        this.trainId = trainId;
        this.linkId = Math.max(0, linkId);
        this.attachOffsetMeters = Math.max(0, attachOffsetMeters);
        this.direction = direction == null ? ExternalTrainDirection.UNKNOWN : direction;
        this.state = state;
        this.signalNetworkStatus = signalNetworkStatus;
        this.powerNetworkStatus = powerNetworkStatus;
        this.reason = reason;
    }

    public static ExternalTrainControlSession connecting(
        String trainId,
        int linkId,
        double attachOffsetMeters,
        ExternalTrainDirection direction
    ) {
        return new ExternalTrainControlSession(
            trainId,
            linkId,
            attachOffsetMeters,
            direction,
            ExternalTrainControlSessionState.CONNECTING,
            "NOT_ATTACHED",
            "NOT_ATTACHED",
            "EXTERNAL_CONTROL_CONNECTING"
        );
    }

    public static ExternalTrainControlSession inService(
        String trainId,
        int linkId,
        double attachOffsetMeters,
        ExternalTrainDirection direction
    ) {
        return new ExternalTrainControlSession(
            trainId,
            linkId,
            attachOffsetMeters,
            direction,
            ExternalTrainControlSessionState.IN_SERVICE,
            "ATTACHED",
            "ATTACHED",
            "EXTERNAL_CONTROL_IN_SERVICE"
        );
    }

    public void advance(MovementAuthority authority, PowerConstraint power) {
        switch (state) {
            case CONNECTING -> transition(
                ExternalTrainControlSessionState.SIGNAL_ATTACHING,
                "ATTACHING",
                "NOT_ATTACHED",
                "EXTERNAL_CONTROL_CONNECTED"
            );
            case SIGNAL_ATTACHING -> {
                if (authority == null) {
                    reason = "WAITING_SIGNAL_NETWORK";
                    signalNetworkStatus = "ATTACHING";
                } else {
                    transition(
                        ExternalTrainControlSessionState.POWER_ATTACHING,
                        "ATTACHED",
                        "ATTACHING",
                        "SIGNAL_NETWORK_ATTACHED"
                    );
                }
            }
            case POWER_ATTACHING -> {
                if (power == null || power.currentCollectionAvailable()) {
                    transition(
                        ExternalTrainControlSessionState.ONLINE_STANDBY,
                        "ATTACHED",
                        "ATTACHED",
                        "POWER_NETWORK_ATTACHED"
                    );
                } else {
                    reason = "WAITING_POWER_NETWORK";
                    powerNetworkStatus = "ATTACHING";
                }
            }
            case ONLINE_STANDBY -> transition(
                ExternalTrainControlSessionState.IN_SERVICE,
                "ATTACHED",
                "ATTACHED",
                "EXTERNAL_CONTROL_IN_SERVICE"
            );
            case SIGNAL_DETACHING -> transition(
                ExternalTrainControlSessionState.POWER_DETACHING,
                "DETACHED",
                "DETACHING",
                "SIGNAL_NETWORK_DETACHED"
            );
            case POWER_DETACHING -> transition(
                ExternalTrainControlSessionState.DISCONNECTED,
                "DETACHED",
                "DETACHED",
                "POWER_NETWORK_DETACHED"
            );
            case IN_SERVICE, DISCONNECTED -> {
                // Stable states advance only by explicit attach/detach command.
            }
        }
    }

    public void requestDetach(String detachReason) {
        if (state == ExternalTrainControlSessionState.DISCONNECTED
            || state == ExternalTrainControlSessionState.POWER_DETACHING) {
            return;
        }
        transition(
            ExternalTrainControlSessionState.SIGNAL_DETACHING,
            "DETACHING",
            powerNetworkStatus,
            detachReason == null || detachReason.isBlank() ? "SIGNAL_DELETE_TRAIN" : detachReason
        );
    }

    public boolean inService() {
        return state == ExternalTrainControlSessionState.IN_SERVICE;
    }

    public boolean disconnected() {
        return state == ExternalTrainControlSessionState.DISCONNECTED;
    }

    public String trainId() {
        return trainId;
    }

    public int linkId() {
        return linkId;
    }

    public double attachOffsetMeters() {
        return attachOffsetMeters;
    }

    public ExternalTrainDirection direction() {
        return direction;
    }

    public ExternalTrainControlSessionState state() {
        return state;
    }

    public String signalNetworkStatus() {
        return signalNetworkStatus;
    }

    public String powerNetworkStatus() {
        return powerNetworkStatus;
    }

    public String reason() {
        return reason;
    }

    private void transition(
        ExternalTrainControlSessionState nextState,
        String nextSignalNetworkStatus,
        String nextPowerNetworkStatus,
        String nextReason
    ) {
        state = nextState;
        signalNetworkStatus = nextSignalNetworkStatus;
        powerNetworkStatus = nextPowerNetworkStatus;
        reason = nextReason;
    }
}
