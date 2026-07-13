package com.railwaysim.dispatch.route;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.dispatch.DispatchCommand;
import com.railwaysim.dispatch.DispatchCommandFeedback;
import com.railwaysim.dispatch.command.CommandStatus;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InterlockingFeedbackParserTests {

    @Test
    void occupiedRouteIsClassifiedAsRetryableResourceConflict() {
        DispatchCommand command = command("REQUEST_ROUTE");
        InterlockingFeedback parsed = InterlockingFeedbackParser.parse(command, feedback(
            command, "Route R_MAIN is occupied by another train"));

        assertThat(parsed.accepted()).isFalse();
        assertThat(parsed.resultCode()).isEqualTo("INTERLOCKING_RESOURCE_BUSY");
        assertThat(parsed.failureCategory()).isEqualTo("RESOURCE_CONFLICT");
        assertThat(parsed.retryable()).isTrue();
    }

    @Test
    void invalidCancellationAfterTrainEntryIsNotRetryable() {
        DispatchCommand command = command("CANCEL_ROUTE");
        InterlockingFeedback parsed = InterlockingFeedbackParser.parse(command, feedback(
            command, "Route R_MAIN cannot be cancelled after a train has entered"));

        assertThat(parsed.resultCode()).isEqualTo("ROUTE_CANCEL_NOT_ALLOWED");
        assertThat(parsed.failureCategory()).isEqualTo("CANCEL_NOT_ALLOWED");
        assertThat(parsed.retryable()).isFalse();
    }

    @Test
    void structuredRetryableFlagIsPreservedForUnclassifiedReason() {
        DispatchCommand command = command("REQUEST_ROUTE");
        DispatchCommandFeedback feedback = new DispatchCommandFeedback(
            command.id(), command.trainId(), command.commandType(), "SIGNAL_INTERLOCKING",
            CommandStatus.SKIPPED, "Route is transitioning", Instant.EPOCH,
            Map.of("accepted", false, "rawReason", "Route is transitioning",
                "resultCode", "ROUTE_NOT_AVAILABLE", "retryable", true));

        InterlockingFeedback parsed = InterlockingFeedbackParser.parse(command, feedback);

        assertThat(parsed.resultCode()).isEqualTo("ROUTE_NOT_AVAILABLE");
        assertThat(parsed.retryable()).isTrue();
    }

    private static DispatchCommand command(String type) {
        return new DispatchCommand("DC-1", "TR-1", type, Map.of("routeId", "R_MAIN"),
            "test", CommandStatus.PENDING, Instant.EPOCH, null);
    }

    private static DispatchCommandFeedback feedback(DispatchCommand command, String reason) {
        return new DispatchCommandFeedback(command.id(), command.trainId(), command.commandType(),
            "SIGNAL_INTERLOCKING", CommandStatus.SKIPPED, reason, Instant.EPOCH,
            Map.of("accepted", false, "rawReason", reason));
    }
}
