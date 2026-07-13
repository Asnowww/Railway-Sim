package com.railwaysim.dispatch.operation;

public final class OperationPlanStatus {

    public static final String PLANNED = "PLANNED";
    public static final String ROUTE_REQUESTED = "ROUTE_REQUESTED";
    public static final String ROUTE_ACCEPTED = "ROUTE_ACCEPTED";
    public static final String ROUTE_REJECTED = "ROUTE_REJECTED";
    public static final String CANCELLED = "CANCELLED";

    private OperationPlanStatus() {
    }
}
