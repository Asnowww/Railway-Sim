package com.railwaysim.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.dispatch.command.CommandQueue;
import com.railwaysim.dispatch.command.CommandStatus;
import com.railwaysim.dispatch.command.CommandValidator;
import com.railwaysim.dispatch.command.InMemoryCommandRecordStore;
import com.railwaysim.dispatch.config.DispatchProperties;
import com.railwaysim.dispatch.disturbance.DisturbanceDetector;
import com.railwaysim.dispatch.disturbance.InMemoryDisturbanceRecordStore;
import com.railwaysim.dispatch.monitor.InMemoryStationRecordStore;
import com.railwaysim.dispatch.monitor.TrainRunMonitor;
import com.railwaysim.dispatch.plan.OperationPlanLoader;
import com.railwaysim.dispatch.plan.PlannedScheduleCalculator;
import com.railwaysim.dispatch.route.RouteDecisionStatus;
import com.railwaysim.dispatch.route.RouteCatalog;
import com.railwaysim.dispatch.route.RouteDispatchRecordStore;
import com.railwaysim.dispatch.route.RouteIntentResolver;
import com.railwaysim.dispatch.route.RouteIntentArbiter;
import com.railwaysim.dispatch.route.RouteReservationState;
import com.railwaysim.dispatch.strategy.StrategySelector;
import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.signal.RouteState;
import com.railwaysim.signal.RouteStatus;
import com.railwaysim.simulation.TickContext;
import com.railwaysim.train.TrainEntity;
import com.railwaysim.train.TrainState;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class DispatchServiceTests {

    private final DispatchService dispatchService = dispatchService();

    @Test
    void submittedHoldCommandForcesZeroSpeedLimit() {
        dispatchService.submit(command("TR-1", "HOLD", null));

        List<DispatchConstraint> constraints = dispatchService.constraintsForTrains(List.of(train("TR-1")));

        assertThat(constraints).hasSize(1);
        DispatchConstraint constraint = constraints.get(0);
        assertThat(constraint.holdTrain()).isTrue();
        assertThat(constraint.applyToSpeedLimit(20)).isZero();
    }

    @Test
    void manualSpeedLimitCommandProjectsToPreviewAndApplyPaths() {
        dispatchService.submit(command("TR-1", "SPEED_LIMIT", "8"));

        List<DispatchConstraint> preview = dispatchService.previewConstraintsForTrains(List.of(train("TR-1")));

        assertThat(preview.get(0).targetSpeedMetersPerSecond()).isEqualTo(8);
        assertThat(preview.get(0).sourceCommandIds()).containsExactly("DC-test-SPEED_LIMIT");
        assertThat(dispatchService.pendingCommands()).isEmpty();
        assertThat(dispatchService.commands()).hasSize(1);

        List<DispatchConstraint> applied = dispatchService.constraintsForTrains(List.of(train("TR-1")));

        assertThat(applied.get(0).targetSpeedMetersPerSecond()).isEqualTo(8);
    }

    @Test
    void submittedRerouteCommandIsQueuedForInterlocking() {
        dispatchService.submit(command("TR-1", "REROUTE", "R_BRANCH"));

        assertThat(dispatchService.pendingCommands())
            .extracting(DispatchCommand::commandType)
            .containsExactly("REROUTE");
        assertThat(dispatchService.drainCommandsOfType("REROUTE"))
            .extracting(DispatchCommand::id)
            .containsExactly("DC-test-REROUTE");
        assertThat(dispatchService.pendingCommands()).isEmpty();
    }

    @Test
    void submittedRequestRouteCommandIsQueuedForInterlocking() {
        dispatchService.submit(commandWithPayload("TR-1", "REQUEST_ROUTE", Map.of("routeId", "R_BRANCH")));

        dispatchService.constraintsForTrains(List.of(train("TR-1")));

        assertThat(dispatchService.drainCommandsOfType("REQUEST_ROUTE"))
            .extracting(DispatchCommand::id)
            .containsExactly("DC-test-REQUEST_ROUTE");
        assertThat(dispatchService.pendingCommands()).isEmpty();
    }

    @Test
    void submittedRequestRouteCommandCreatesRouteDecisionAndReservation() {
        dispatchService.submit(commandWithPayload("TR-1", "REQUEST_ROUTE", Map.of("routeId", "R_BRANCH")));

        DispatchSnapshot snapshot = dispatchService.snapshot();

        assertThat(snapshot.routeDispatchActive()).isTrue();
        assertThat(snapshot.routeDecisions())
            .extracting(DispatchSnapshot.RouteDecisionView::selectedRouteId)
            .containsExactly("R_BRANCH");
        assertThat(snapshot.routeDecisions())
            .extracting(DispatchSnapshot.RouteDecisionView::status)
            .containsExactly(RouteDecisionStatus.REQUESTED);
        assertThat(snapshot.routeReservations())
            .extracting(DispatchSnapshot.RouteReservationView::state)
            .containsExactly(RouteReservationState.REQUESTED);
    }

    @Test
    void routeInterlockingFeedbackAcceptsRouteReservation() {
        dispatchService.submit(commandWithPayload("TR-1", "REQUEST_ROUTE", Map.of("routeId", "R_BRANCH")));

        dispatchService.acceptFeedback(List.of(new DispatchCommandFeedback(
            "DC-test-REQUEST_ROUTE",
            "TR-1",
            "REQUEST_ROUTE",
            "SIGNAL_INTERLOCKING",
            CommandStatus.EFFECT_CONFIRMED,
            "route established",
            Instant.parse("2026-07-09T00:00:00Z"),
            Map.of("accepted", true)
        )));

        assertThat(dispatchService.snapshot().routeDecisions())
            .extracting(DispatchSnapshot.RouteDecisionView::status)
            .containsExactly(RouteDecisionStatus.ACCEPTED);
        assertThat(dispatchService.snapshot().routeReservations())
            .extracting(DispatchSnapshot.RouteReservationView::state)
            .containsExactly(RouteReservationState.ACCEPTED);
    }

    @Test
    void routeReservationIsReleasedWhenInterlockingRouteIsNoLongerEstablished() {
        dispatchService.submit(commandWithPayload("TR-1", "REQUEST_ROUTE", Map.of("routeId", "R_BRANCH")));
        dispatchService.acceptFeedback(List.of(new DispatchCommandFeedback(
            "DC-test-REQUEST_ROUTE",
            "TR-1",
            "REQUEST_ROUTE",
            "SIGNAL_INTERLOCKING",
            CommandStatus.EFFECT_CONFIRMED,
            "route established",
            Instant.parse("2026-07-09T00:00:00Z"),
            Map.of("accepted", true)
        )));

        dispatchService.syncRouteReservations(List.of(new RouteState(
            "R_BRANCH",
            RouteStatus.AVAILABLE,
            Set.of(),
            null,
            Set.of("T01")
        )), Instant.parse("2026-07-09T00:01:00Z"));

        assertThat(dispatchService.snapshot().routeReservations())
            .extracting(DispatchSnapshot.RouteReservationView::state)
            .containsExactly(RouteReservationState.RELEASED);
    }

    @Test
    void invalidRouteCommandIsRejectedBeforeInterlockingQueue() {
        dispatchService.submit(commandWithPayload("TR-1", "REQUEST_ROUTE", Map.of()));

        assertThat(dispatchService.pendingCommands()).isEmpty();
        assertThat(dispatchService.commands())
            .extracting(DispatchCommand::status)
            .containsExactly(CommandStatus.SKIPPED);
        assertThat(dispatchService.snapshot().routeDecisions())
            .extracting(DispatchSnapshot.RouteDecisionView::status)
            .containsExactly(RouteDecisionStatus.REJECTED);
        assertThat(dispatchService.snapshot().routeDecisions().get(0).rejectReason())
            .contains("route command requires routeId or detail");
    }

    @Test
    void expiredManualRouteCommandIsRejectedBeforeInterlockingQueue() {
        dispatchService.submit(commandWithPayload("TR-1", "REQUEST_ROUTE", Map.of(
            "routeId", "R_BRANCH",
            "validUntil", "2000-01-01T00:00:00Z"
        )));

        assertThat(dispatchService.pendingCommands()).isEmpty();
        assertThat(dispatchService.commands())
            .extracting(DispatchCommand::status)
            .containsExactly(CommandStatus.EXPIRED);
        assertThat(dispatchService.snapshot().routeReservations())
            .extracting(DispatchSnapshot.RouteReservationView::state)
            .containsExactly(RouteReservationState.EXPIRED);
    }

    @Test
    void evaluateCreatesAutomaticRouteRequestWhenTrainApproachesRouteEntry() {
        DispatchService service = dispatchService(routeLineData());
        Instant now = Instant.parse("2026-07-09T00:00:00Z");

        service.evaluate(
            tick(1, now),
            List.of(runningTrainAt("TR-1", 100, 8)),
            List.of(new MovementAuthority("TR-1", 500, 15, "TEST", "T01", "T02", "TEST"))
        );

        assertThat(service.pendingCommands())
            .extracting(DispatchCommand::commandType)
            .containsExactly("REQUEST_ROUTE");
        assertThat(service.pendingCommands().get(0).payload())
            .containsEntry("routeId", "R_MAIN")
            .containsEntry("autoGenerated", true)
            .containsEntry("source", "AUTO_ROUTE_INTENT");
        assertThat(service.snapshot().routeReservations())
            .extracting(DispatchSnapshot.RouteReservationView::routeId)
            .containsExactly("R_MAIN");
    }

    @Test
    void automaticRouteRequestIsNotDuplicatedWhileReservationIsActive() {
        DispatchService service = dispatchService(routeLineData());
        Instant now = Instant.parse("2026-07-09T00:00:00Z");

        service.evaluate(
            tick(1, now),
            List.of(runningTrainAt("TR-1", 100, 8)),
            List.of(new MovementAuthority("TR-1", 500, 15, "TEST", "T01", "T02", "TEST"))
        );
        service.evaluate(
            tick(2, now.plusSeconds(1)),
            List.of(runningTrainAt("TR-1", 120, 8)),
            List.of(new MovementAuthority("TR-1", 500, 15, "TEST", "T01", "T02", "TEST"))
        );

        assertThat(service.commands())
            .filteredOn(command -> "REQUEST_ROUTE".equals(command.commandType()))
            .hasSize(1);
        assertThat(service.pendingCommands())
            .filteredOn(command -> "REQUEST_ROUTE".equals(command.commandType()))
            .hasSize(1);
    }

    @Test
    void dispatchGeneratesDepartureCommandsFromFormalServicePlan() {
        DispatchService service = dispatchService();
        Instant due = Instant.now().plusSeconds(1_400);

        service.evaluate(tick(1, due), List.of(), List.of());

        assertThat(service.pendingCommands())
            .filteredOn(command -> "DEPART".equals(command.commandType()))
            .hasSize(4)
            .allSatisfy(command -> assertThat(command.payload())
                .containsKeys("serviceId", "circulationId", "plannedDepartureAt", "fromStation", "toStation")
                .containsEntry("source", "FORMAL_SERVICE_PLAN"));
        assertThat(service.snapshot().services())
            .extracting(DispatchSnapshot.ServicePlanView::departureStatus)
            .containsOnly(CommandStatus.PENDING);
    }

    @Test
    void pendingRouteRequestTimesOutAtDispatchAcknowledgementDeadline() {
        DispatchService service = dispatchService();
        Instant now = Instant.now();
        DispatchCommand request = new DispatchCommand(
            "DC-route-timeout", "TR-1", "REQUEST_ROUTE", Map.of("routeId", "R_BRANCH"),
            "test", CommandStatus.PENDING, now, null
        );
        service.submit(request);

        service.evaluate(tick(1, now.plusSeconds(31)), List.of(), List.of());

        assertThat(service.snapshot().routeReservations()).singleElement().satisfies(reservation -> {
            assertThat(reservation.state()).isEqualTo(RouteReservationState.TIMEOUT);
            assertThat(reservation.failureCode()).isEqualTo("INTERLOCKING_ACK_TIMEOUT");
            assertThat(reservation.retryable()).isTrue();
            assertThat(reservation.timedOutAt()).isNotNull();
        });
        assertThat(service.commands()).filteredOn(command -> command.id().equals("DC-route-timeout"))
            .extracting(DispatchCommand::status).containsExactly(CommandStatus.TIMEOUT);
    }

    @Test
    void cancellingAcceptedRouteQueuesRealInterlockingCancellationCommand() {
        DispatchService service = dispatchService();
        Instant now = Instant.now();
        DispatchCommand request = service.submit(new DispatchCommand(
            "DC-route-cancel", "TR-1", "REQUEST_ROUTE", Map.of("routeId", "R_BRANCH"),
            "test", CommandStatus.PENDING, now, null
        ));
        service.acceptFeedback(List.of(new DispatchCommandFeedback(
            request.id(), request.trainId(), request.commandType(), "SIGNAL_INTERLOCKING",
            CommandStatus.EFFECT_CONFIRMED, "route established", now,
            Map.of("accepted", true, "routeId", "R_BRANCH", "resultCode", "ROUTE_ESTABLISHED")
        )));

        service.cancelCommand(request.id());

        assertThat(service.pendingCommands())
            .filteredOn(command -> "CANCEL_ROUTE".equals(command.commandType()))
            .singleElement()
            .satisfies(command -> assertThat(command.payload())
                .containsEntry("routeId", "R_BRANCH")
                .containsKey("reservationId"));
        assertThat(service.snapshot().routeReservations()).singleElement()
            .satisfies(reservation -> assertThat(reservation.cancelCommandId()).isNotBlank());

        DispatchCommand cancellation = service.drainCommandsOfType("CANCEL_ROUTE").getFirst();
        service.acceptFeedback(List.of(new DispatchCommandFeedback(
            cancellation.id(), cancellation.trainId(), cancellation.commandType(), "SIGNAL_INTERLOCKING",
            CommandStatus.EFFECT_CONFIRMED, "route cancelled", now.plusSeconds(1),
            Map.of("accepted", true, "routeId", "R_BRANCH", "resultCode", "ROUTE_CANCELLED")
        )));
        assertThat(service.snapshot().routeReservations())
            .extracting(DispatchSnapshot.RouteReservationView::state)
            .containsExactly(RouteReservationState.CANCELLED);
    }

    @Test
    void acceptedRouteHoldTimeoutAutomaticallyQueuesCancellation() {
        DispatchService service = dispatchService();
        Instant now = Instant.now();
        DispatchCommand request = service.submit(new DispatchCommand(
            "DC-route-hold-timeout", "TR-1", "REQUEST_ROUTE", Map.of("routeId", "R_BRANCH"),
            "test", CommandStatus.PENDING, now, null
        ));
        service.acceptFeedback(List.of(new DispatchCommandFeedback(
            request.id(), request.trainId(), request.commandType(), "SIGNAL_INTERLOCKING",
            CommandStatus.EFFECT_CONFIRMED, "route established", now,
            Map.of("accepted", true, "routeId", "R_BRANCH", "resultCode", "ROUTE_ESTABLISHED")
        )));

        service.evaluate(tick(1, now.plusSeconds(121)), List.of(), List.of());

        assertThat(service.pendingCommands())
            .filteredOn(command -> "CANCEL_ROUTE".equals(command.commandType()))
            .singleElement()
            .satisfies(command -> assertThat(command.reason()).isEqualTo("ROUTE_HOLD_TIMEOUT"));
    }

    @Test
    void automaticRouteArbitrationSelectsOneTrainAndRecordsTheWaitingTrain() {
        DispatchService service = dispatchService(routeLineData());
        Instant now = Instant.parse("2026-07-09T00:00:00Z");

        service.evaluate(
            tick(1, now),
            List.of(
                runningTrainAt("TR-1", 100, 8),
                runningTrainAt("TR-2", 150, 8)
            ),
            List.of(
                new MovementAuthority("TR-1", 500, 15, "TEST", "T01", "T02", "TEST"),
                new MovementAuthority("TR-2", 500, 15, "TEST", "T01", "T02", "TEST")
            )
        );

        assertThat(service.pendingCommands())
            .filteredOn(command -> "REQUEST_ROUTE".equals(command.commandType()))
            .singleElement()
            .satisfies(command -> assertThat(command.trainId()).isEqualTo("TR-2"));
        assertThat(service.snapshot().routeDecisions())
            .singleElement()
            .satisfies(decision -> {
                assertThat(decision.selectedTrainId()).isEqualTo("TR-2");
                assertThat(decision.waitingTrainIds()).containsExactly("TR-1");
            });

        service.evaluate(
            tick(2, now.plusSeconds(1)),
            List.of(
                runningTrainAt("TR-1", 110, 8),
                runningTrainAt("TR-2", 160, 8)
            ),
            List.of(
                new MovementAuthority("TR-1", 500, 15, "TEST", "T01", "T02", "TEST"),
                new MovementAuthority("TR-2", 500, 15, "TEST", "T01", "T02", "TEST")
            )
        );
        assertThat(service.commands())
            .filteredOn(command -> "REQUEST_ROUTE".equals(command.commandType()))
            .hasSize(1);
    }

    @Test
    void waitingTimeBonusAllowsPreviouslyWaitingTrainToWinNextArbitration() {
        DispatchService service = dispatchService(routeLineData());
        Instant now = Instant.parse("2026-07-09T00:00:00Z");
        List<TrainState> trains = List.of(
            runningTrainAt("TR-1", 100, 8),
            runningTrainAt("TR-2", 150, 8)
        );
        List<MovementAuthority> authorities = List.of(
            new MovementAuthority("TR-1", 500, 15, "TEST", "T01", "T02", "TEST"),
            new MovementAuthority("TR-2", 500, 15, "TEST", "T01", "T02", "TEST")
        );

        service.evaluate(tick(1, now), trains, authorities);
        DispatchCommand first = service.drainCommandsOfType("REQUEST_ROUTE").getFirst();
        assertThat(first.trainId()).isEqualTo("TR-2");
        service.acceptFeedback(List.of(new DispatchCommandFeedback(
            first.id(), first.trainId(), first.commandType(), "SIGNAL_INTERLOCKING", CommandStatus.SKIPPED,
            "Route R_MAIN conflicts with an occupied route", now,
            Map.of("accepted", false, "rawReason", "Route R_MAIN conflicts with an occupied route")
        )));

        service.evaluate(tick(2, now.plusSeconds(21)), trains, authorities);

        assertThat(service.pendingCommands())
            .filteredOn(command -> "REQUEST_ROUTE".equals(command.commandType()))
            .singleElement()
            .satisfies(command -> {
                assertThat(command.trainId()).isEqualTo("TR-1");
                assertThat(command.payload().get("waitingSeconds")).isEqualTo(21.0);
            });
    }

    @Test
    void targetedDwellCommandWaitsForItsConfiguredStation() {
        dispatchService.submit(commandWithPayload("TR-1", "SHORTEN_DWELL", Map.of(
            "deltaDwellSec", -5,
            "targetStationId", "S02",
            "executeOnNextDwell", true
        )));

        DispatchConstraint constraint = dispatchService.previewConstraintsForTrains(List.of(dwellingTrain("TR-1", 20))).getFirst();

        assertThat(constraint.sourceCommandIds()).isEmpty();
        assertThat(constraint.reason()).contains("waitingForStation=S02");
    }

    @Test
    void pendingAutomaticRouteReservationExpiresAtItsIntentValidityDeadline() {
        DispatchService service = dispatchService(routeLineData());
        Instant now = Instant.parse("2026-07-09T00:00:00Z");

        service.evaluate(
            tick(1, now),
            List.of(runningTrainAt("TR-1", 100, 8)),
            List.of(new MovementAuthority("TR-1", 500, 15, "TEST", "T01", "T02", "TEST"))
        );
        service.evaluate(
            tick(2, now.plusSeconds(91)),
            List.of(runningTrainAt("TR-1", 120, 8)),
            List.of(new MovementAuthority("TR-1", 500, 15, "TEST", "T01", "T02", "TEST"))
        );

        assertThat(service.snapshot().routeReservations())
            .extracting(DispatchSnapshot.RouteReservationView::state)
            .containsExactly(RouteReservationState.EXPIRED);
        assertThat(service.commands())
            .filteredOn(command -> "REQUEST_ROUTE".equals(command.commandType()))
            .extracting(DispatchCommand::status)
            .containsExactly(CommandStatus.EXPIRED);
    }

    @Test
    void rejectedAutomaticRouteRequestRetriesAfterCooldownAndIncrementsRetryCount() {
        DispatchService service = dispatchService(routeLineData());
        Instant now = Instant.parse("2026-07-09T00:00:00Z");
        List<MovementAuthority> authorities = List.of(
            new MovementAuthority("TR-1", 500, 15, "TEST", "T01", "T02", "TEST")
        );

        service.evaluate(tick(1, now), List.of(runningTrainAt("TR-1", 100, 8)), authorities);
        DispatchCommand first = service.drainCommandsOfType("REQUEST_ROUTE").getFirst();
        service.acceptFeedback(List.of(new DispatchCommandFeedback(
            first.id(),
            first.trainId(),
            first.commandType(),
            "SIGNAL_INTERLOCKING",
            CommandStatus.SKIPPED,
            "ROUTE_CONFLICT",
            now,
            Map.of("rejectCode", "ROUTE_CONFLICT")
        )));

        service.evaluate(tick(2, now.plusSeconds(21)), List.of(runningTrainAt("TR-1", 120, 8)), authorities);

        assertThat(service.pendingCommands())
            .filteredOn(command -> "REQUEST_ROUTE".equals(command.commandType()))
            .hasSize(1);
        assertThat(service.snapshot().routeReservations())
            .extracting(DispatchSnapshot.RouteReservationView::retryCount)
            .containsExactly(0, 1);
    }

    @Test
    void nonRetryableRouteConfigurationErrorDoesNotGenerateAnotherRequest() {
        DispatchService service = dispatchService(routeLineData());
        Instant now = Instant.parse("2026-07-09T00:00:00Z");
        List<MovementAuthority> authorities = List.of(
            new MovementAuthority("TR-1", 500, 15, "TEST", "T01", "T02", "TEST")
        );
        service.evaluate(tick(1, now), List.of(runningTrainAt("TR-1", 100, 8)), authorities);
        DispatchCommand first = service.drainCommandsOfType("REQUEST_ROUTE").getFirst();
        String reason = "No matching route for detail=R_MAIN";
        service.acceptFeedback(List.of(new DispatchCommandFeedback(
            first.id(), first.trainId(), first.commandType(), "SIGNAL_INTERLOCKING", CommandStatus.SKIPPED,
            reason, now, Map.of("accepted", false, "rawReason", reason)
        )));

        service.evaluate(tick(2, now.plusSeconds(60)),
            List.of(runningTrainAt("TR-1", 120, 8)), authorities);

        assertThat(service.pendingCommands())
            .filteredOn(command -> "REQUEST_ROUTE".equals(command.commandType()))
            .isEmpty();
        assertThat(service.snapshot().routeReservations()).singleElement().satisfies(reservation -> {
            assertThat(reservation.failureCode()).isEqualTo("ROUTE_NOT_FOUND");
            assertThat(reservation.retryable()).isFalse();
        });
    }

    @Test
    void extendDwellCommandHoldsDwellingTrainUntilAdjustedTarget() {
        dispatchService.submit(commandWithPayload("TR-1", "EXTEND_DWELL", Map.of("deltaDwellSec", 10)));

        DispatchConstraint constraint = dispatchService.previewConstraintsForTrains(List.of(dwellingTrain("TR-1", 20))).get(0);

        assertThat(constraint.holdTrain()).isTrue();
        assertThat(constraint.releaseStationStop()).isFalse();
        assertThat(constraint.applyToSpeedLimit(20)).isZero();
    }

    @Test
    void defaultDwellTargetReleasesDwellingTrainAfterPlannedStop() {
        int targetDwell = dispatchService.currentPlan().defaultDwellTimeSec();

        DispatchConstraint holding = dispatchService.previewConstraintsForTrains(
            List.of(dwellingTrain("TR-1", Math.max(0, targetDwell - 1)))
        ).get(0);
        DispatchConstraint released = dispatchService.previewConstraintsForTrains(
            List.of(dwellingTrain("TR-1", targetDwell))
        ).get(0);

        assertThat(holding.holdTrain()).isTrue();
        assertThat(holding.releaseStationStop()).isFalse();
        assertThat(holding.sourceCommandIds()).isEmpty();
        assertThat(released.holdTrain()).isFalse();
        assertThat(released.releaseStationStop()).isTrue();
        assertThat(released.sourceCommandIds()).isEmpty();
    }

    @Test
    void shortenDwellCommandReleasesStationStopAfterAdjustedTarget() {
        dispatchService.submit(commandWithPayload("TR-1", "SHORTEN_DWELL", Map.of("deltaDwellSec", -5)));
        int targetDwell = Math.max(15, dispatchService.currentPlan().defaultDwellTimeSec() - 5);

        DispatchConstraint constraint = dispatchService.previewConstraintsForTrains(List.of(dwellingTrain("TR-1", targetDwell))).get(0);

        assertThat(constraint.holdTrain()).isFalse();
        assertThat(constraint.releaseStationStop()).isTrue();
        assertThat(constraint.applyToSpeedLimit(20)).isEqualTo(20);
    }

    @Test
    void scheduledDwellCompletionReleasesStationStopWithoutInterventionCommand() {
        int targetDwell = dispatchService.currentPlan().defaultDwellTimeSec();

        DispatchConstraint constraint = dispatchService.previewConstraintsForTrains(List.of(dwellingTrain("TR-1", targetDwell))).get(0);

        assertThat(constraint.holdTrain()).isFalse();
        assertThat(constraint.releaseStationStop()).isTrue();
        assertThat(constraint.reason()).contains("SCHEDULED_DWELL_COMPLETE");
    }

    @Test
    void scheduledDwellCompletionDoesNotOverrideHoldCommand() {
        dispatchService.submit(commandWithPayload("TR-1", "EXTEND_DWELL", Map.of("deltaDwellSec", 10)));
        int targetDwell = dispatchService.currentPlan().defaultDwellTimeSec();

        DispatchConstraint constraint = dispatchService.previewConstraintsForTrains(List.of(dwellingTrain("TR-1", targetDwell))).get(0);

        assertThat(constraint.holdTrain()).isTrue();
        assertThat(constraint.releaseStationStop()).isFalse();
    }

    @Test
    void stationDwellCompletionDoesNotReleaseTrainIntoCloseFrontTrain() {
        int targetDwell = dispatchService.currentPlan().defaultDwellTimeSec();

        List<DispatchConstraint> constraints = dispatchService.previewConstraintsForTrains(List.of(
            dwellingTrainAt("TR-1", 1000, targetDwell),
            dwellingTrainAt("TR-2", 1245, 10)
        ));

        DispatchConstraint rear = constraintFor(constraints, "TR-1");
        assertThat(rear.holdTrain()).isTrue();
        assertThat(rear.releaseStationStop()).isFalse();
        assertThat(rear.reason()).contains("STATION_HOLD_FOR_HEADWAY");
    }

    @Test
    void runningTrainApproachingStoppedFrontTrainGetsSpeedRegulationBeforeMaExhaustion() {
        List<DispatchConstraint> constraints = dispatchService.previewConstraintsForTrains(List.of(
            runningTrainAt("TR-1", 1000, 8),
            dwellingTrainAt("TR-2", 1245, 10)
        ));

        DispatchConstraint rear = constraintFor(constraints, "TR-1");
        assertThat(rear.holdTrain()).isFalse();
        assertThat(rear.targetSpeedMetersPerSecond()).isNotNull();
        assertThat(rear.applyToSpeedLimit(20)).isLessThan(20);
        assertThat(rear.reason()).contains("APPROACH_CONTROL_FOR_HEADWAY");
    }

    @Test
    void headwayAdjustCommandCanReleaseMinimumDwellForShorterHeadway() {
        dispatchService.submit(commandWithPayload(
            "TR-1",
            "HEADWAY_ADJUST",
            Map.of("targetHeadwaySec", dispatchService.currentPlan().departureIntervalSec() - 1)
        ));

        DispatchConstraint constraint = dispatchService.previewConstraintsForTrains(List.of(dwellingTrain("TR-1", 15))).get(0);

        assertThat(constraint.releaseStationStop()).isTrue();
    }

    @Test
    void runningTrainWithStaleDwellSecondsDoesNotTriggerDwellDisturbance() {
        Instant now = Instant.parse("2026-07-09T00:00:00Z");

        for (int i = 0; i < 3; i++) {
            dispatchService.evaluate(
                tick(i + 1, now.plusSeconds(i)),
                List.of(runningTrainWithStaleDwell("TR-1", 120)),
                List.of(authority("TR-1"))
            );
        }

        assertThat(dispatchService.snapshot().openDisturbances()).isEmpty();
        assertThat(dispatchService.snapshot().activeCommands()).isEmpty();
    }

    @Test
    void publishedCommandIsSentBeforeVehicleStateConfirmsApplication() {
        dispatchService.markCommandsSent(List.of(commandWithPayload("TR-1", "SHORTEN_DWELL", Map.of("deltaDwellSec", -5))));

        assertThat(dispatchService.snapshot().activeCommands())
            .extracting(DispatchSnapshot.CommandView::status)
            .containsExactly(CommandStatus.SENT);
    }

    @Test
    void structuredFeedbackUpdatesOriginalCommandWithoutCreatingSyntheticCommand() {
        dispatchService.submit(commandWithPayload("TR-1", "SPEED_LIMIT", Map.of("detail", "8")));

        dispatchService.acceptFeedback(List.of(new DispatchCommandFeedback(
            "DC-test-SPEED_LIMIT",
            "TR-1",
            "SPEED_LIMIT",
            "SIGNAL_RUNTIME",
            CommandStatus.APPLIED,
            "target speed observed",
            Instant.parse("2026-07-09T00:00:00Z"),
            Map.of("actualSpeed", 7.8)
        )));

        assertThat(dispatchService.commands()).hasSize(1);
        assertThat(dispatchService.commands().get(0).id()).isEqualTo("DC-test-SPEED_LIMIT");
        assertThat(dispatchService.commands().get(0).status()).isEqualTo(CommandStatus.APPLIED);
        assertThat(dispatchService.commands().get(0).payload())
            .containsEntry("lastFeedbackSource", "SIGNAL_RUNTIME")
            .containsEntry("lastFeedbackStatus", CommandStatus.APPLIED);
    }

    @Test
    void repeatedAppliedFeedbackIsIgnoredAfterFirstObservation() {
        dispatchService.submit(commandWithPayload("TR-1", "SPEED_LIMIT", Map.of("detail", "8")));

        dispatchService.acceptFeedback(List.of(new DispatchCommandFeedback(
            "DC-test-SPEED_LIMIT",
            "TR-1",
            "SPEED_LIMIT",
            "SIGNAL_RUNTIME",
            CommandStatus.APPLIED,
            "first observation",
            Instant.parse("2026-07-09T00:00:00Z"),
            Map.of("actualSpeed", 7.8)
        )));
        dispatchService.acceptFeedback(List.of(new DispatchCommandFeedback(
            "DC-test-SPEED_LIMIT",
            "TR-1",
            "SPEED_LIMIT",
            "SIGNAL_RUNTIME",
            CommandStatus.APPLIED,
            "second observation",
            Instant.parse("2026-07-09T00:00:01Z"),
            Map.of("actualSpeed", 7.4)
        )));

        assertThat(dispatchService.commands()).hasSize(1);
        assertThat(dispatchService.commands().get(0).payload())
            .containsEntry("lastFeedbackReason", "first observation")
            .containsEntry("lastFeedbackAt", "2026-07-09T00:00:00Z");
    }

    @Test
    void effectConfirmedManualCommandStopsProducingControlConstraint() {
        dispatchService.submit(commandWithPayload("TR-1", "SPEED_LIMIT", Map.of("detail", "8")));

        dispatchService.acceptFeedback(List.of(new DispatchCommandFeedback(
            "DC-test-SPEED_LIMIT",
            "TR-1",
            "SPEED_LIMIT",
            "SIGNAL_RUNTIME",
            CommandStatus.EFFECT_CONFIRMED,
            "effect confirmed",
            Instant.parse("2026-07-09T00:00:00Z"),
            Map.of("actualSpeed", 7.8)
        )));

        DispatchConstraint constraint = dispatchService.previewConstraintsForTrains(List.of(train("TR-1"))).get(0);
        assertThat(constraint.targetSpeedMetersPerSecond()).isNull();
        assertThat(constraint.sourceCommandIds()).isEmpty();
    }

    @Test
    void appliedManualSpeedLimitExitsControlAfterEffectConfirmation() {
        Instant now = Instant.parse("2026-07-09T00:00:00Z");
        dispatchService.submit(commandWithPayload("TR-1", "SPEED_LIMIT", Map.of("detail", "8")));

        dispatchService.acceptFeedback(List.of(new DispatchCommandFeedback(
            "DC-test-SPEED_LIMIT",
            "TR-1",
            "SPEED_LIMIT",
            "SIGNAL_RUNTIME",
            CommandStatus.APPLIED,
            "speed limit observed",
            now,
            Map.of("actualSpeed", 7.8)
        )));
        dispatchService.evaluate(
            tick(1, now.plusSeconds(1)),
            List.of(train("TR-1")),
            List.of(new MovementAuthority("TR-1", 500, 8, "TEST", "SEG-TEST", "SEG-END", "NORMAL"))
        );

        DispatchConstraint constraint = dispatchService.previewConstraintsForTrains(List.of(train("TR-1"))).get(0);
        assertThat(dispatchService.commands().get(0).status()).isEqualTo(CommandStatus.EFFECT_CONFIRMED);
        assertThat(constraint.targetSpeedMetersPerSecond()).isNull();
        assertThat(constraint.sourceCommandIds()).isEmpty();
    }

    @Test
    void manualSpeedLimitRequiresExplicitCancellation() {
        Instant now = Instant.parse("2026-07-09T00:00:00Z");
        DispatchCommand command = new DispatchCommand(
            "DC-manual-limit",
            "TR-1",
            "SPEED_LIMIT",
            Map.of("detail", "8"),
            "MANUAL",
            CommandStatus.PENDING,
            now,
            null
        );
        dispatchService.submit(command);

        dispatchService.acceptFeedback(List.of(new DispatchCommandFeedback(
            "DC-manual-limit",
            "TR-1",
            "SPEED_LIMIT",
            "SIGNAL_RUNTIME",
            CommandStatus.APPLIED,
            "speed limit observed",
            now,
            Map.of("actualSpeed", 7.8)
        )));
        dispatchService.evaluate(
            tick(1, now.plusSeconds(1)),
            List.of(train("TR-1")),
            List.of(new MovementAuthority("TR-1", 500, 8, "TEST", "SEG-TEST", "SEG-END", "NORMAL"))
        );

        DispatchConstraint activeConstraint = dispatchService.previewConstraintsForTrains(List.of(train("TR-1"))).get(0);
        assertThat(dispatchService.commands().get(0).status()).isEqualTo(CommandStatus.APPLIED);
        assertThat(activeConstraint.targetSpeedMetersPerSecond()).isEqualTo(8);
        assertThat(activeConstraint.sourceCommandIds()).containsExactly("DC-manual-limit");

        dispatchService.cancelCommand("DC-manual-limit");

        DispatchConstraint cancelledConstraint = dispatchService.previewConstraintsForTrains(List.of(train("TR-1"))).get(0);
        assertThat(dispatchService.commands().get(0).status()).isEqualTo(CommandStatus.CANCELLED);
        assertThat(cancelledConstraint.targetSpeedMetersPerSecond()).isNull();
        assertThat(cancelledConstraint.sourceCommandIds()).isEmpty();
    }

    @Test
    void shortenDwellCommandStaysAppliedUntilDisturbanceRecoveryConfirmsEffect() {
        Instant now = Instant.parse("2026-07-09T00:00:00Z");
        dispatchService.markCommandsSent(List.of(commandWithPayload("TR-1", "SHORTEN_DWELL", Map.of("deltaDwellSec", -5))));

        dispatchService.evaluate(
            tick(1, now),
            List.of(dwellingTrain("TR-1", 60)),
            List.of(authority("TR-1"))
        );

        assertThat(dispatchService.snapshot().activeCommands())
            .extracting(DispatchSnapshot.CommandView::status)
            .containsExactly(CommandStatus.APPLIED);

        dispatchService.evaluate(
            tick(2, now.plusSeconds(2)),
            List.of(train("TR-1")),
            List.of(authority("TR-1"))
        );

        assertThat(dispatchService.snapshot().activeCommands())
            .extracting(DispatchSnapshot.CommandView::status)
            .containsExactly(CommandStatus.APPLIED);
    }

    @Test
    void appliedHeadwayAdjustTimesOutWhenHeadwayCannotBeConfirmed() {
        Instant now = Instant.parse("2026-07-09T00:00:00Z");
        DispatchCommand command = new DispatchCommand(
            "DC-headway-adjust",
            "TR-1",
            "HEADWAY_ADJUST",
            Map.of("targetHeadwaySec", dispatchService.currentPlan().departureIntervalSec() + 240),
            "test",
            CommandStatus.PENDING,
            now,
            null
        );
        dispatchService.markCommandsSent(List.of(command));
        dispatchService.acceptFeedback(List.of(new DispatchCommandFeedback(
            "DC-headway-adjust",
            "TR-1",
            "HEADWAY_ADJUST",
            "SIGNAL_RUNTIME",
            CommandStatus.APPLIED,
            "headway hold observed",
            now,
            Map.of("zeroSpeed", true)
        )));

        dispatchService.evaluate(
            tick(1, now.plusSeconds(dispatchServiceTimeoutSec() + 1L)),
            List.of(dwellingTrain("TR-1", 60)),
            List.of(authority("TR-1"))
        );

        assertThat(dispatchService.commands())
            .filteredOn(commandView -> commandView.id().equals("DC-headway-adjust"))
            .extracting(DispatchCommand::status)
            .containsExactly(CommandStatus.TIMEOUT);
        assertThat(dispatchService.snapshot().activeCommands())
            .filteredOn(commandView -> commandView.id().equals("DC-headway-adjust"))
            .extracting(DispatchSnapshot.CommandView::status)
            .containsExactly(CommandStatus.TIMEOUT);
        assertThat(dispatchService.previewConstraintsForTrains(List.of(dwellingTrain("TR-1", 60))).get(0).sourceCommandIds())
            .isEmpty();
    }

    private static DispatchCommand command(String trainId, String type, String detail) {
        Map<String, Object> payload = detail == null ? Map.of() : Map.of("detail", detail);
        return commandWithPayload(trainId, type, payload);
    }

    private static DispatchCommand commandWithPayload(String trainId, String type, Map<String, Object> payload) {
        return new DispatchCommand(
            "DC-test-" + type,
            trainId,
            type,
            payload,
            "test",
            CommandStatus.PENDING,
            Instant.now(),
            null
        );
    }

    private static TrainState train(String id) {
        return new TrainEntity(id, "test-line", 50, 20).state();
    }

    private static DispatchConstraint constraintFor(List<DispatchConstraint> constraints, String trainId) {
        return constraints.stream()
            .filter(constraint -> constraint.trainId().equals(trainId))
            .findFirst()
            .orElseThrow();
    }

    private static MovementAuthority authority(String trainId) {
        return new MovementAuthority(trainId, 500, 15, "TEST", "SEG-TEST", "SEG-END", "NORMAL");
    }

    private static TickContext tick(long tick, Instant simulatedTime) {
        return new TickContext(tick, 1000, 1.0, simulatedTime);
    }

    private static int dispatchServiceTimeoutSec() {
        return new DispatchProperties().getCommandEffectTimeoutSec();
    }

    private static TrainState dwellingTrain(String id, int dwellElapsedSeconds) {
        return dwellingTrainAt(id, 50, dwellElapsedSeconds);
    }

    private static TrainState dwellingTrainAt(String id, double positionMeters, int dwellElapsedSeconds) {
        return new TrainState(
            id,
            "test-line",
            id,
            "IN_SERVICE",
            "ATTACHED",
            "ATTACHED",
            "TEST",
            0,
            "UNKNOWN",
            positionMeters,
            0,
            20,
            positionMeters,
            Math.max(0, positionMeters - 20),
            0.35,
            25_200,
            "NORMAL",
            6,
            6,
            "NONE",
            "DWELLING",
            "STATION_CONTROL",
            true,
            "CLOSED_LOCKED",
            "IDLE",
            "APPLYING",
            "NORMAL",
            true,
            true,
            "PASS",
            0,
            "NORMAL",
            "GOOD",
            "STATION_STOPPED",
            "TEST",
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            "OK",
            "S01",
            dwellElapsedSeconds,
            null
        );
    }

    private static TrainState runningTrainAt(String id, double positionMeters, double speedMetersPerSecond) {
        return new TrainState(
            id,
            "test-line",
            id,
            "IN_SERVICE",
            "ATTACHED",
            "ATTACHED",
            "TEST",
            0,
            "UNKNOWN",
            positionMeters,
            speedMetersPerSecond,
            20,
            positionMeters,
            Math.max(0, positionMeters - 20),
            0.35,
            25_200,
            "NORMAL",
            6,
            6,
            "NONE",
            "RUNNING",
            "ATO",
            false,
            "CLOSED_LOCKED",
            "APPLYING",
            "RELEASED",
            "NORMAL",
            true,
            true,
            "PASS",
            0,
            "NORMAL",
            "GOOD",
            "ACCELERATING",
            "SPEED_MARGIN_AVAILABLE",
            15,
            0,
            300,
            1_000_000,
            100,
            0.3,
            20_000,
            0,
            0,
            100,
            50_000,
            0,
            0,
            0,
            "OK",
            null,
            0,
            null
        );
    }

    private static TrainState runningTrainWithStaleDwell(String id, int dwellElapsedSeconds) {
        return new TrainState(
            id,
            "test-line",
            id,
            "IN_SERVICE",
            "ATTACHED",
            "ATTACHED",
            "TEST",
            0,
            "UNKNOWN",
            50,
            8,
            20,
            50,
            30,
            0.35,
            25_200,
            "NORMAL",
            6,
            6,
            "NONE",
            "RUNNING",
            "ATO",
            false,
            "CLOSED_LOCKED",
            "APPLYING",
            "RELEASED",
            "NORMAL",
            true,
            true,
            "PASS",
            0,
            "NORMAL",
            "GOOD",
            "ACCELERATING",
            "SPEED_MARGIN_AVAILABLE",
            15,
            0,
            300,
            1_000_000,
            100,
            0.3,
            20_000,
            0,
            0,
            100,
            50_000,
            0,
            0,
            0,
            "OK",
            null,
            dwellElapsedSeconds,
            null
        );
    }

    private static DispatchService dispatchService() {
        return dispatchService(emptyLineData());
    }

    private static DispatchService dispatchService(OperationalLineData lineData) {
        try {
            DispatchProperties properties = new DispatchProperties();
            OperationPlanLoader planLoader = new OperationPlanLoader(properties, new DefaultResourceLoader());
            planLoader.load();
            InMemoryStationRecordStore stationStore = new InMemoryStationRecordStore();
            RouteDispatchRecordStore routeStore = new RouteDispatchRecordStore();
            RouteCatalog routeCatalog = new RouteCatalog(lineData);
            return new DispatchService(
                planLoader,
                properties,
                new TrainRunMonitor(planLoader, new PlannedScheduleCalculator(properties), stationStore),
                new DisturbanceDetector(properties),
                new StrategySelector(),
                new CommandValidator(),
                new CommandQueue(),
                new InMemoryDisturbanceRecordStore(),
                new InMemoryCommandRecordStore(),
                stationStore,
                routeStore,
                new RouteIntentResolver(routeCatalog, properties),
                new RouteIntentArbiter(routeCatalog)
            );
        } catch (IOException ex) {
            throw new IllegalStateException("failed to load dispatch test plan", ex);
        }
    }

    private static OperationalLineData emptyLineData() {
        return new OperationalLineData(
            "test-line",
            "test-line",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private static OperationalLineData routeLineData() {
        return new OperationalLineData(
            "test-line",
            "test-line",
            List.of(),
            List.of(
                segment("T01", 0, 400, "main"),
                segment("T02", 400, 1000, "main"),
                segment("T03", 400, 1000, "branch")
            ),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new OperationalLineData.RouteDefinition(
                    "R_BRANCH",
                    "branch",
                    "DIVERGING",
                    null,
                    null,
                    List.of("T03"),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
                ),
                new OperationalLineData.RouteDefinition(
                    "R_MAIN",
                    "main",
                    "MAIN",
                    null,
                    null,
                    List.of("T02"),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
                )
            )
        );
    }

    private static OperationalLineData.TrackSegmentDefinition segment(
        String id,
        double startMeters,
        double endMeters,
        String track
    ) {
        return new OperationalLineData.TrackSegmentDefinition(
            id,
            0,
            startMeters,
            endMeters,
            endMeters - startMeters,
            20,
            0,
            0,
            0,
            0,
            List.of(),
            List.of(),
            "N" + Math.round(startMeters),
            "N" + Math.round(endMeters),
            track
        );
    }
}
