package com.railwaysim.dispatch.route;

public final class RouteReservationState {

    public static final String REQUESTED = "REQUESTED";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String RELEASED = "RELEASED";
    public static final String EXPIRED = "EXPIRED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String TIMEOUT = "TIMEOUT";

    private RouteReservationState() {
    }
}
