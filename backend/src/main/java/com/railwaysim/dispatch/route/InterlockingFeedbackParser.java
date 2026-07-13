package com.railwaysim.dispatch.route;

import com.railwaysim.dispatch.DispatchCommand;
import com.railwaysim.dispatch.DispatchCommandFeedback;
import java.util.Locale;
import java.util.Map;

public final class InterlockingFeedbackParser {

    private InterlockingFeedbackParser() {
    }

    public static InterlockingFeedback parse(DispatchCommand command, DispatchCommandFeedback feedback) {
        Map<String, Object> details = feedback.details();
        boolean accepted = booleanValue(details.get("accepted"));
        String reason = firstNonBlank(stringValue(details.get("rawReason")), feedback.reason());
        String routeId = firstNonBlank(stringValue(details.get("routeId")), RouteDispatchRecordStore.routeIdFrom(command));
        String state = stringValue(details.get("interlockingState"));
        String explicitCode = stringValue(details.get("resultCode"));
        Boolean explicitRetryable = nullableBooleanValue(details.get("retryable"));
        if (accepted) {
            return new InterlockingFeedback(true, firstNonBlank(explicitCode, "ROUTE_ESTABLISHED"),
                "NONE", false, routeId, state, reason);
        }

        String normalized = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        String commandType = command == null ? "" : command.commandType();
        if (normalized.contains("does not exist") || normalized.contains("no matching route")
            || normalized.contains("not found")) {
            return rejected(classifiedCode(explicitCode, "ROUTE_NOT_FOUND"), "CONFIGURATION", retryable(explicitRetryable, false),
                routeId, state, reason);
        }
        if (normalized.contains("unsupported") || normalized.contains("invalid")
            || normalized.contains("requires route")) {
            return rejected(classifiedCode(explicitCode, "INVALID_ROUTE_REQUEST"), "INVALID_REQUEST", retryable(explicitRetryable, false),
                routeId, state, reason);
        }
        if ("CANCEL_ROUTE".equals(commandType)
            && (normalized.contains("after a train has entered") || normalized.contains("cannot be cancelled"))) {
            return rejected(classifiedCode(explicitCode, "ROUTE_CANCEL_NOT_ALLOWED"), "CANCEL_NOT_ALLOWED", retryable(explicitRetryable, false),
                routeId, state, reason);
        }
        if (normalized.contains("occupied") || normalized.contains("conflict")
            || normalized.contains("locked") || normalized.contains("reserved")) {
            return rejected(classifiedCode(explicitCode, "INTERLOCKING_RESOURCE_BUSY"), "RESOURCE_CONFLICT", retryable(explicitRetryable, true),
                routeId, state, reason);
        }
        if (normalized.contains("switch") || normalized.contains("道岔")) {
            return rejected(classifiedCode(explicitCode, "SWITCH_UNAVAILABLE"), "SWITCH_STATE", retryable(explicitRetryable, true),
                routeId, state, reason);
        }
        if (normalized.contains("timeout") || normalized.contains("超时")
            || normalized.contains("temporar") || normalized.contains("busy")) {
            return rejected(classifiedCode(explicitCode, "INTERLOCKING_TEMPORARY_FAILURE"), "TEMPORARY", retryable(explicitRetryable, true),
                routeId, state, reason);
        }
        return rejected(firstNonBlank(explicitCode, "INTERLOCKING_REJECTED"), "UNKNOWN", retryable(explicitRetryable, false),
            routeId, state, reason);
    }

    private static InterlockingFeedback rejected(
        String code,
        String category,
        boolean retryable,
        String routeId,
        String state,
        String reason
    ) {
        return new InterlockingFeedback(false, code, category, retryable, routeId, state, reason);
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean flag ? flag : value != null && Boolean.parseBoolean(value.toString());
    }

    private static Boolean nullableBooleanValue(Object value) {
        return value == null ? null : booleanValue(value);
    }

    private static boolean retryable(Boolean explicitValue, boolean fallback) {
        return explicitValue == null ? fallback : explicitValue;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String classifiedCode(String explicitCode, String classifiedCode) {
        return explicitCode == null || explicitCode.isBlank() || "INTERLOCKING_REJECTED".equals(explicitCode)
            ? classifiedCode
            : explicitCode;
    }
}
