package com.railwaysim.vehicle.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class VehicleControlArbiterTests {

    private static final String RUN_ID = "test-run-001";
    private static final String TRAIN_ID = "TR-001";
    private static final long TICK = 42;

    @Test
    void noCandidatesProducesEmergencyBrake() {
        var arbiter = new VehicleControlArbiter(RUN_ID, TICK, TRAIN_ID);
        VehicleControlDecision decision = arbiter.arbitrate();

        assertAll(
            () -> assertThat(decision.emergencyBrake()).isTrue(),
            () -> assertThat(decision.brakeCommand()).isEqualTo(1.0),
            () -> assertThat(decision.tractionCommand()).isEqualTo(0.0),
            () -> assertThat(decision.source()).isEqualTo("FALLBACK"),
            () -> assertThat(decision.selectedReasonCode()).isEqualTo("EMERGENCY_BRAKE"),
            () -> assertThat(decision.runId()).isEqualTo(RUN_ID),
            () -> assertThat(decision.tick()).isEqualTo(TICK),
            () -> assertThat(decision.trainId()).isEqualTo(TRAIN_ID)
        );
    }

    @Test
    void emergencyBrakeOverridesDriverTraction() {
        var cmd = driverCommand(0.8, 0.0, false);
        var arbiter = new VehicleControlArbiter(RUN_ID, TICK, TRAIN_ID)
            .withSafetyCandidate(VehicleSafetyConstraint.EMERGENCY_BRAKE, 0.0, 1.0, true, "EMERGENCY_BRAKE")
            .withDriverCandidate(cmd, true, true, true, true);

        VehicleControlDecision decision = arbiter.arbitrate();
        assertAll(
            () -> assertThat(decision.emergencyBrake()).isTrue(),
            () -> assertThat(decision.tractionCommand()).isEqualTo(0.0),
            () -> assertThat(decision.brakeCommand()).isEqualTo(1.0),
            () -> assertThat(decision.selectedReasonCode()).isEqualTo("EMERGENCY_BRAKE")
        );
    }

    @Test
    void doorOpenBlocksTraction() {
        var cmd = driverCommand(0.8, 0.0, false);
        var arbiter = new VehicleControlArbiter(RUN_ID, TICK, TRAIN_ID)
            .withSafetyCandidate(VehicleSafetyConstraint.DOOR_KEY_SELFCHECK, 0.0, 0.0, false, "DOOR_KEY_BLOCK")
            .withDriverCandidate(cmd, false, true, true, true);

        VehicleControlDecision decision = arbiter.arbitrate();
        assertAll(
            () -> assertThat(decision.tractionCommand()).isEqualTo(0.0),
            () -> assertThat(decision.emergencyBrake()).isFalse(),
            () -> assertThat(decision.selectedReasonCode()).isEqualTo("DOOR_KEY_BLOCK")
        );
    }

    @Test
    void powerLossBlocksTraction() {
        var cmd = driverCommand(0.5, 0.0, false);
        var arbiter = new VehicleControlArbiter(RUN_ID, TICK, TRAIN_ID)
            .withSafetyCandidate(VehicleSafetyConstraint.POWER_CONSTRAINT, 0.0, 0.3, false, "POWER_LOSS")
            .withDriverCandidate(cmd, true, false, true, true);

        VehicleControlDecision decision = arbiter.arbitrate();
        assertAll(
            () -> assertThat(decision.tractionCommand()).isEqualTo(0.0),
            () -> assertThat(decision.currentCollectionAvailable()).isFalse(),
            () -> assertThat(decision.selectedReasonCode()).isEqualTo("POWER_LOSS")
        );
    }

    @Test
    void maBrakeOverridesDriverTraction() {
        var cmd = driverCommand(0.8, 0.0, false);
        var arbiter = new VehicleControlArbiter(RUN_ID, TICK, TRAIN_ID)
            .withSafetyCandidate(VehicleSafetyConstraint.MA_ATP, 0.0, 0.5, false, "MA_ATP_LIMIT")
            .withDriverCandidate(cmd, true, true, true, true);

        VehicleControlDecision decision = arbiter.arbitrate();
        assertAll(
            () -> assertThat(decision.tractionCommand()).isEqualTo(0.0),
            () -> assertThat(decision.brakeCommand()).isEqualTo(0.5),
            () -> assertThat(decision.selectedReasonCode()).isEqualTo("MA_ATP_LIMIT")
        );
    }

    @Test
    void manualOverridesAuto() {
        var driverCmd = driverCommand(0.5, 0.0, false);
        var autoCmd = new AutomaticControlCommand("auto-1", TRAIN_ID, "ATO", 0.0, 0.3, false, 1.0, "trace-auto");

        var decision = VehicleControlArbiter.decide(
            RUN_ID, TICK, TRAIN_ID,
            Optional.of(driverCmd), Optional.of(autoCmd),
            false, false, true, true, true, true, true,
            VehicleOperationMode.MANUAL, false
        );

        assertAll(
            () -> assertThat(decision.source()).isEqualTo("DRIVER"),
            () -> assertThat(decision.tractionCommand()).isEqualTo(0.5),
            () -> assertThat(decision.selectedReasonCode()).isEqualTo("TRACTION_COMMANDED")
        );
    }

    @Test
    void priorityMatrixEmergencyWins() {
        var cmd = driverCommand(0.8, 0.0, false);

        var decision = VehicleControlArbiter.decide(
            RUN_ID, TICK, TRAIN_ID,
            Optional.of(cmd), Optional.empty(),
            true, false, true, true, true, true, true,
            VehicleOperationMode.MANUAL, false
        );

        assertAll(
            () -> assertThat(decision.emergencyBrake()).isTrue(),
            () -> assertThat(decision.tractionCommand()).isEqualTo(0.0),
            () -> assertThat(decision.brakeCommand()).isEqualTo(1.0),
            () -> assertThat(decision.selectedReasonCode()).isEqualTo("EMERGENCY_BRAKE")
        );
    }

    @Test
    void powerLossWithDriverCommandDisablesTraction() {
        var cmd = driverCommand(0.8, 0.0, false);

        var decision = VehicleControlArbiter.decide(
            RUN_ID, TICK, TRAIN_ID,
            Optional.of(cmd), Optional.empty(),
            false, false, true, false, true, true, false,
            VehicleOperationMode.MANUAL, false
        );

        assertAll(
            () -> assertThat(decision.emergencyBrake()).isFalse(),
            () -> assertThat(decision.tractionCommand()).isEqualTo(0.0),
            () -> assertThat(decision.currentCollectionAvailable()).isFalse(),
            () -> assertThat(decision.selectedReasonCode()).isEqualTo("POWER_LOSS")
        );
    }

    @Test
    void doorOpenHasHigherPriorityThanPowerLoss() {
        var cmd = driverCommand(0.8, 0.0, false);

        var decision = VehicleControlArbiter.decide(
            RUN_ID, TICK, TRAIN_ID,
            Optional.of(cmd), Optional.empty(),
            false, false, false, false, true, true, false,
            VehicleOperationMode.MANUAL, false
        );

        assertAll(
            () -> assertThat(decision.tractionCommand()).isEqualTo(0.0),
            () -> assertThat(decision.selectedReasonCode()).isIn("DOOR_KEY_BLOCK", "POWER_LOSS")
        );
    }

    @Test
    void normalDriverTractionSucceeds() {
        var cmd = driverCommand(0.6, 0.0, false);

        var decision = VehicleControlArbiter.decide(
            RUN_ID, TICK, TRAIN_ID,
            Optional.of(cmd), Optional.empty(),
            false, false, true, true, true, true, true,
            VehicleOperationMode.MANUAL, false
        );

        assertAll(
            () -> assertThat(decision.emergencyBrake()).isFalse(),
            () -> assertThat(decision.tractionCommand()).isEqualTo(0.6),
            () -> assertThat(decision.selectedReasonCode()).isEqualTo("TRACTION_COMMANDED"),
            () -> assertThat(decision.source()).isEqualTo("DRIVER")
        );
    }

    @Test
    void autoCoastingWhenNoCommand() {
        var decision = VehicleControlArbiter.decide(
            RUN_ID, TICK, TRAIN_ID,
            Optional.empty(), Optional.empty(),
            false, false, true, true, true, true, true,
            VehicleOperationMode.AUTO, false
        );

        assertAll(
            () -> assertThat(decision.emergencyBrake()).isFalse(),
            () -> assertThat(decision.tractionCommand()).isEqualTo(0.0),
            () -> assertThat(decision.brakeCommand()).isEqualTo(0.0),
            () -> assertThat(decision.selectedReasonCode()).isEqualTo("COASTING")
        );
    }

    @Test
    void decisionContainsAuditFields() {
        var cmd = driverCommand(0.3, 0.0, false);
        var decision = VehicleControlArbiter.decide(
            RUN_ID, TICK, TRAIN_ID,
            Optional.of(cmd), Optional.empty(),
            false, false, true, true, true, true, true,
            VehicleOperationMode.MANUAL, false
        );

        assertAll(
            () -> assertThat(decision.runId()).isEqualTo(RUN_ID),
            () -> assertThat(decision.tick()).isEqualTo(TICK),
            () -> assertThat(decision.trainId()).isEqualTo(TRAIN_ID),
            () -> assertThat(decision.decisionVersion()).isEqualTo(1),
            () -> assertThat(decision.traceId()).isNotBlank(),
            () -> assertThat(decision.decidedAt()).isNotNull(),
            () -> assertThat(decision.inputTimestamp()).isNotNull(),
            () -> assertThat(decision.overriddenCandidateIds()).isNotNull()
        );
    }

    private DriverControlCommand driverCommand(double traction, double brake, boolean eb) {
        return new DriverControlCommand(
            null, TRAIN_ID, 1, Instant.now(),
            Instant.now().plus(5, ChronoUnit.SECONDS),
            traction, brake, eb, 1.0,
            false, false, "MANUAL", "trace-" + TRAIN_ID, null
        );
    }
}
