package com.railwaysim.simulation;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.runtime.VehicleRuntimeTrainStep;
import jakarta.annotation.PreDestroy;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Persists the final decisions selected by the authoritative 9300 runtime.
 * The simulation thread only performs a bounded non-blocking enqueue; JDBC work runs outside its lock.
 */
@Service
public class FinalControlDecisionPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(FinalControlDecisionPersistenceService.class);
    private static final String UPSERT_SQL = """
        INSERT INTO vehicle_control_command_log (
            simulation_run_id, tick, train_id, command_id, trace_id,
            operation_mode, decision_source, traction_command, brake_command,
            emergency_brake, reason_code, decided_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
            command_id = VALUES(command_id), trace_id = VALUES(trace_id),
            operation_mode = VALUES(operation_mode), decision_source = VALUES(decision_source),
            traction_command = VALUES(traction_command), brake_command = VALUES(brake_command),
            emergency_brake = VALUES(emergency_brake), reason_code = VALUES(reason_code),
            decided_at = VALUES(decided_at)
        """;

    private final JdbcTemplate jdbcTemplate;
    private final BlockingQueue<List<FinalControlDecision>> queue;
    private final AtomicInteger pendingBatches = new AtomicInteger();
    private final Thread worker;
    private volatile boolean running = true;

    public FinalControlDecisionPersistenceService(JdbcTemplate jdbcTemplate, SimulationProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.queue = new ArrayBlockingQueue<>(properties.getControlDecisionQueueCapacity());
        this.worker = new Thread(this::runWorker, "final-control-decision-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** Returns false only when the bounded queue is full; it never waits for MySQL. */
    public boolean persistFinalControlDecisions(TickContext context, List<VehicleRuntimeTrainStep> trainSteps) {
        List<FinalControlDecision> decisions = (trainSteps == null ? List.<VehicleRuntimeTrainStep>of() : trainSteps)
            .stream()
            .filter(step -> step != null && step.report() != null)
            .map(step -> map(context, step))
            .toList();
        if (decisions.isEmpty()) {
            return true;
        }
        pendingBatches.incrementAndGet();
        if (!queue.offer(decisions)) {
            pendingBatches.decrementAndGet();
            log.error(
                "Final control decision queue is full; run={} tick={} decisions={}",
                context.simulationRunId(), context.tick(), decisions.size()
            );
            return false;
        }
        return true;
    }

    public int pendingBatchCount() {
        return pendingBatches.get();
    }

    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (pendingBatches.get() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return pendingBatches.get() == 0;
    }

    private FinalControlDecision map(TickContext context, VehicleRuntimeTrainStep step) {
        TrainStateReport report = step.report();
        String reasonCode = firstNonBlank(
            report.selectedReasonCode(), report.dynamicsConstraintReason(), "UNSPECIFIED"
        );
        return new FinalControlDecision(
            context.simulationRunId(), context.tick(), step.trainId(),
            report.inputCommandId(), report.inputTraceId(),
            firstNonBlank(report.operationMode(), "UNKNOWN"),
            firstNonBlank(report.decisionSource(), "CONTROL_OR_SAFETY"),
            report.tractionCommand(), report.brakeCommand(), report.emergencyBrakeCommand(),
            reasonCode, Instant.now()
        );
    }

    private void runWorker() {
        while (running || !queue.isEmpty()) {
            try {
                List<FinalControlDecision> batch = queue.poll(250, TimeUnit.MILLISECONDS);
                if (batch == null) {
                    continue;
                }
                persistWithRetry(batch);
                pendingBatches.decrementAndGet();
            } catch (InterruptedException interrupted) {
                if (!running) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void persistWithRetry(List<FinalControlDecision> batch) throws InterruptedException {
        long retryDelayMillis = 50;
        while (running) {
            try {
                batchUpsert(batch);
                return;
            } catch (DataAccessException failure) {
                log.warn(
                    "Final control decision persistence unavailable; retaining batch of {} for retry: {}",
                    batch.size(), failure.getMessage()
                );
                Thread.sleep(retryDelayMillis);
                retryDelayMillis = Math.min(1000, retryDelayMillis * 2);
            }
        }
    }

    private void batchUpsert(List<FinalControlDecision> decisions) {
        jdbcTemplate.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws java.sql.SQLException {
                FinalControlDecision value = decisions.get(index);
                statement.setString(1, value.simulationRunId());
                statement.setLong(2, value.tick());
                statement.setString(3, value.trainId());
                statement.setString(4, value.commandId());
                statement.setString(5, value.traceId());
                statement.setString(6, value.operationMode());
                statement.setString(7, value.decisionSource());
                statement.setDouble(8, value.tractionCommand());
                statement.setDouble(9, value.brakeCommand());
                statement.setBoolean(10, value.emergencyBrake());
                statement.setString(11, value.reasonCode());
                statement.setTimestamp(12, Timestamp.from(value.decidedAt()));
            }

            @Override
            public int getBatchSize() {
                return decisions.size();
            }
        });
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    @PreDestroy
    public void close() {
        running = false;
        worker.interrupt();
        try {
            worker.join(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record FinalControlDecision(
        String simulationRunId,
        long tick,
        String trainId,
        String commandId,
        String traceId,
        String operationMode,
        String decisionSource,
        double tractionCommand,
        double brakeCommand,
        boolean emergencyBrake,
        String reasonCode,
        Instant decidedAt
    ) {
    }
}
