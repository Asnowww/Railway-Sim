package com.railwaysim.vehicleruntime.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.model.MovementAuthoritySnapshot;
import com.railwaysim.vehicleruntime.model.PowerConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.TrackConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.TrainStateSnapshot;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeStepRequest;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeStepResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class VehicleRuntimeManagerTests {

    @Test
    void registerCreatesPairedRuntimeInstance() {
        VehicleRuntimeManager manager = manager();

        var state = manager.register(train("TR-101", 100, 0));

        assertThat(state.trainId()).isEqualTo("TR-101");
        assertThat(state.lifecycleState()).isEqualTo("READY");
        assertThat(manager.instances()).singleElement()
            .satisfies(instance -> assertThat(instance.trainId()).isEqualTo("TR-101"));
    }

    @Test
    void stepFleetRunsControlAndSimulationQueues() {
        VehicleRuntimeManager manager = manager();

        VehicleRuntimeStepResponse response = manager.stepFleet(request(1, train("TR-101", 100, 0), energized()));

        assertThat(response.dataQuality()).isEqualTo("GOOD");
        assertThat(response.trainOutputs()).singleElement()
            .satisfies(output -> {
                assertThat(output.trainId()).isEqualTo("TR-101");
                assertThat(output.newPositionMeters()).isGreaterThan(100);
                assertThat(output.tractionPowerWatts()).isGreaterThan(0);
                assertThat(output.railCurrentAmps()).isGreaterThan(0);
            });
        assertThat(response.trainReports()).singleElement()
            .satisfies(report -> assertThat(report.dynamicsState()).isIn("ACCELERATING", "CRUISING", "COASTING"));
    }

    @Test
    void duplicateTickIsRejectedInsteadOfQueued() {
        VehicleRuntimeManager manager = manager();
        manager.stepFleet(request(1, train("TR-101", 100, 0), energized()));

        VehicleRuntimeStepResponse duplicate = manager.stepFleet(request(1, train("TR-101", 100, 0), energized()));

        assertThat(duplicate.dataQuality()).isEqualTo("DEGRADED");
        assertThat(duplicate.trainOutputs()).isEmpty();
        assertThat(duplicate.instanceStates()).singleElement()
            .satisfies(state -> {
                assertThat(state.controlQueueStatus()).isEqualTo("REJECTED");
                assertThat(state.reason()).isEqualTo("STALE_OR_DUPLICATE_TICK");
            });
    }

    @Test
    void powerLossForcesControlQueueToBrakeAndReportCurrentCollectionLost() {
        VehicleRuntimeManager manager = manager();

        VehicleRuntimeStepResponse response = manager.stepFleet(request(1, train("TR-101", 100, 8), deenergized()));

        assertThat(response.trainReports()).singleElement()
            .satisfies(report -> {
                assertThat(report.dynamicsState()).isEqualTo("POWER_LOSS");
                assertThat(report.currentCollectionStatus()).isEqualTo("LOST");
                assertThat(report.faultLevel()).isEqualTo(3);
            });
    }

    private VehicleRuntimeManager manager() {
        VehicleRuntimeProperties properties = new VehicleRuntimeProperties();
        properties.setQueueCapacity(1);
        return new VehicleRuntimeManager(properties);
    }

    private VehicleRuntimeStepRequest request(long tick, TrainStateSnapshot train, PowerConstraintSnapshot power) {
        return new VehicleRuntimeStepRequest(
            tick,
            0.2,
            Instant.parse("2026-07-09T00:00:00Z"),
            List.of(train),
            List.of(new MovementAuthoritySnapshot(train.id(), 2_000, 22.2, "NORMAL")),
            List.of(new TrackConstraintSnapshot(train.id(), "SEG-1", 22.2, 0, 1_000, 1_000_000)),
            List.of(power)
        );
    }

    private PowerConstraintSnapshot energized() {
        return new PowerConstraintSnapshot("TR-101", "P01", 1500, 3_200_000, true, 1.0, true, true, "NORMAL");
    }

    private PowerConstraintSnapshot deenergized() {
        return new PowerConstraintSnapshot("TR-101", "P01", 0, 0, false, 0, false, false, "POWER_UNAVAILABLE");
    }

    private TrainStateSnapshot train(String trainId, double position, double speed) {
        return new TrainStateSnapshot(
            trainId,
            "demo-line",
            trainId,
            "IN_SERVICE",
            "ATTACHED",
            "ATTACHED",
            "EXTERNAL_RUNTIME",
            1,
            "DOWN",
            position,
            speed,
            120,
            position,
            Math.max(0, position - 120),
            0.42,
            0,
            "NORMAL",
            4,
            4,
            "NONE",
            "RUNNING",
            "ATO",
            speed <= 0.05,
            "CLOSED_LOCKED",
            "IDLE",
            "RELEASED",
            "NORMAL",
            true,
            true,
            "PASS",
            0,
            "NORMAL",
            "GOOD",
            "COASTING",
            "INITIAL",
            22.2,
            0,
            2_000,
            1_000_000,
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
            "OK"
        );
    }
}
