package com.railwaysim.train;

public enum ExternalTrainControlSessionState {
    CONNECTING,
    SIGNAL_ATTACHING,
    POWER_ATTACHING,
    ONLINE_STANDBY,
    IN_SERVICE,
    SIGNAL_DETACHING,
    POWER_DETACHING,
    DISCONNECTED
}
