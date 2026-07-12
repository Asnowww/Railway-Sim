package com.railwaysim.dispatch.route;

import com.railwaysim.dispatch.DispatchCommand;
import com.railwaysim.dispatch.DispatchCommandFeedback;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class InterlockingFeedbackParser {

    private static final Set<String> STANDARD_INTERLOCKING_CODES = Set.of(
        "ROUTE_ESTABLISHED",
        "ROUTE_NOT_FOUND",
        "NO_MATCHING_ROUTE",
        "TRACK_NOT_FOUND",
        "TRACK_OCCUPIED",
        "TRACK_FAULT",
        "ROUTE_CONFLICT",
        "SWITCH_LOCKED",
        "SWITCH_MOVE_FAILED",
        "ROUTE_NOT_AVAILABLE",
        "SWITCH_POSITION_MISMATCH",
        "UNSUPPORTED_COMMAND",
        "INTERNAL_ERROR"
    );

    private InterlockingFeedbackParser() {
    }

    public static InterlockingFeedback parse(DispatchCommand command, DispatchCommandFeedback feedback) {
        Map<String, Object> details = feedback.details() == null ? Map.of() : feedback.details();
        boolean accepted = booleanValue(details.get("accepted"));
        String reason = firstNonBlank(stringValue(details.get("rawReason")), feedback.reason());
        String routeId = firstNonBlank(stringValue(details.get("routeId")), RouteDispatchRecordStore.routeIdFrom(command));
        String state = stringValue(details.get("interlockingState"));
        String resultCode = stringValue(details.get("resultCode"));
        String failureCode = normalizedCode(stringValue(details.get("failureCode")));
        String structuredCode = structuredCode(failureCode, resultCode);
        Boolean explicitRetryable = booleanValueOrNull(details.get("retryable"));
        if (accepted) {
            return new InterlockingFeedback(true, firstNonBlank(resultCode, firstNonBlank(failureCode, "ROUTE_ESTABLISHED")),
                "NONE", false, routeId, state, reason);
        }
        if (structuredCode != null) {
            return rejected(
                structuredCode,
                categoryForStandardCode(structuredCode),
                explicitRetryable != null ? explicitRetryable : retryableForStandardCode(structuredCode),
                routeId,
                state,
                reason
            );
        }

        String normalized = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        String commandType = command == null ? "" : command.commandType();
        if (normalized.contains("does not exist") || normalized.contains("no matching route")
            || normalized.contains("not found")) {
            return rejected(classifiedCode(resultCode, "ROUTE_NOT_FOUND"), "CONFIGURATION",
                retryableOrDefault(explicitRetryable, false),
                routeId, state, reason);
        }
        if (normalized.contains("unsupported") || normalized.contains("invalid")
            || normalized.contains("requires route")) {
            return rejected(classifiedCode(resultCode, "INVALID_ROUTE_REQUEST"), "INVALID_REQUEST",
                retryableOrDefault(explicitRetryable, false),
                routeId, state, reason);
        }
        if ("CANCEL_ROUTE".equals(commandType)
            && (normalized.contains("after a train has entered") || normalized.contains("cannot be cancelled"))) {
            return rejected(classifiedCode(resultCode, "ROUTE_CANCEL_NOT_ALLOWED"), "CANCEL_NOT_ALLOWED",
                retryableOrDefault(explicitRetryable, false),
                routeId, state, reason);
        }
        if (normalized.contains("occupied") || normalized.contains("conflict")
            || normalized.contains("locked") || normalized.contains("reserved")) {
            return rejected(classifiedCode(resultCode, "INTERLOCKING_RESOURCE_BUSY"), "RESOURCE_CONFLICT",
                retryableOrDefault(explicitRetryable, true),
                routeId, state, reason);
        }
        if (normalized.contains("switch") || normalized.contains("道岔")) {
            return rejected(classifiedCode(resultCode, "SWITCH_UNAVAILABLE"), "SWITCH_STATE",
                retryableOrDefault(explicitRetryable, true),
                routeId, state, reason);
        }
        if (normalized.contains("timeout") || normalized.contains("超时")
            || normalized.contains("temporar") || normalized.contains("busy")) {
            return rejected(classifiedCode(resultCode, "INTERLOCKING_TEMPORARY_FAILURE"), "TEMPORARY",
                retryableOrDefault(explicitRetryable, true),
                routeId, state, reason);
        }
        return rejected(firstNonBlank(resultCode, "INTERLOCKING_REJECTED"), "UNKNOWN",
            retryableOrDefault(explicitRetryable, false),
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

    private static Boolean booleanValueOrNull(Object value) {
        return value == null ? null : booleanValue(value);
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

    private static boolean retryableOrDefault(Boolean retryable, boolean fallback) {
        return retryable == null ? fallback : retryable;
    }

    private static String normalizedCode(String code) {
        return code == null || code.isBlank() ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    private static String structuredCode(String failureCode, String resultCode) {
        if (failureCode != null && STANDARD_INTERLOCKING_CODES.contains(failureCode)) {
            return failureCode;
        }
        String normalizedResultCode = normalizedCode(resultCode);
        if (normalizedResultCode != null
            && STANDARD_INTERLOCKING_CODES.contains(normalizedResultCode)
            && !"ROUTE_ESTABLISHED".equals(normalizedResultCode)) {
            return normalizedResultCode;
        }
        return null;
    }

    private static String categoryForStandardCode(String code) {
        return switch (code) {
            case "ROUTE_NOT_FOUND", "NO_MATCHING_ROUTE", "TRACK_NOT_FOUND" -> "CONFIGURATION";
            case "UNSUPPORTED_COMMAND" -> "INVALID_REQUEST";
            case "TRACK_OCCUPIED", "ROUTE_CONFLICT", "SWITCH_LOCKED" -> "RESOURCE_CONFLICT";
            case "TRACK_FAULT", "SWITCH_MOVE_FAILED", "ROUTE_NOT_AVAILABLE" -> "TEMPORARY";
            case "SWITCH_POSITION_MISMATCH" -> "SWITCH_STATE";
            default -> "UNKNOWN";
        };
    }

    private static boolean retryableForStandardCode(String code) {
        return switch (code) {
            case "TRACK_OCCUPIED", "TRACK_FAULT", "ROUTE_CONFLICT", "SWITCH_LOCKED",
                "SWITCH_MOVE_FAILED", "ROUTE_NOT_AVAILABLE" -> true;
            default -> false;
        };
    }
}
