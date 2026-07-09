package com.railwaysim.dispatch.command;

public final class CommandStatus {

    public static final String PENDING = "PENDING";
    public static final String APPLIED = "APPLIED";
    public static final String EXPIRED = "EXPIRED";
    public static final String SKIPPED = "SKIPPED";
    public static final String CANCELLED = "CANCELLED";
    public static final String COMPLETED = "COMPLETED";

    private CommandStatus() {
    }
}
