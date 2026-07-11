package com.railwaysim.simulation;

import com.railwaysim.config.StoppingControlProperties;
import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.train.TrainState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TrainStopEvaluationService {
    private static final Logger log = LoggerFactory.getLogger(TrainStopEvaluationService.class);

    private final List<OperationalLineData.StationDefinition> stations;
    private final JdbcTemplate jdbcTemplate;
    private final StoppingControlProperties properties;
    private final Map<String, StopTracker> trackers = new LinkedHashMap<>();
    private final List<TrainStopResult> results = new ArrayList<>();

    public TrainStopEvaluationService(StaticInfrastructureCatalog catalog, JdbcTemplate jdbcTemplate,
        StoppingControlProperties properties) {
        this.stations = catalog.lineData().stations();
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public synchronized List<TrainStopResult> evaluate(TickContext context, List<TrainState> trains) {
        List<TrainStopResult> created = new ArrayList<>();
        for (TrainState train : trains) {
            String key = context.simulationRunId() + ":" + train.id();
            StopTracker tracker = trackers.computeIfAbsent(key, ignored -> new StopTracker());
            boolean stoppingEpisode = isStoppingEpisode(train);
            if (stoppingEpisode && !tracker.trackingEpisode) {
                tracker.beginEpisode(context.tick());
            }
            if (tracker.trackingEpisode) {
                tracker.observe(train, context.deltaSeconds());
            }
            if (train.currentStationId() == null || train.currentStationId().isBlank()) {
                if (!stoppingEpisode) {
                    tracker.leaveStation();
                }
                continue;
            }
            if (!train.currentStationId().equals(tracker.stationId)) {
                tracker.enterStation(train.currentStationId(), context.tick());
            }
            boolean stable = "STATION_STOPPED".equals(train.dynamicsState())
                && train.speedMetersPerSecond() <= properties.getZeroSpeedMetersPerSecond()
                && train.dwellElapsedSeconds() >= properties.getStableSeconds();
            if (!stable || tracker.recorded) {
                continue;
            }
            OperationalLineData.StationDefinition station = station(train.currentStationId());
            if (station == null) {
                continue;
            }
            TrainStopResult result = result(context, train, station, tracker);
            tracker.recorded = true;
            results.add(result);
            created.add(result);
            persist(result);
        }
        return List.copyOf(created);
    }

    public synchronized List<TrainStopResult> results(String runId) {
        return results.stream().filter(item -> item.simulationRunId().equals(runId)).toList();
    }

    public synchronized void resetTransientState() {
        trackers.clear();
    }

    private TrainStopResult result(TickContext context, TrainState train,
        OperationalLineData.StationDefinition station, StopTracker tracker) {
        boolean movementAuthorityTarget = Double.isFinite(tracker.movementAuthorityTargetMeters);
        double targetPosition = movementAuthorityTarget
            ? tracker.movementAuthorityTargetMeters : station.centerMeters();
        String targetSource = movementAuthorityTarget ? "MOVEMENT_AUTHORITY" : "STATION";
        double signedError = train.positionMeters() - targetPosition;
        double absoluteError = Math.abs(signedError);
        boolean overrun = signedError > properties.getOverrunThresholdMeters();
        boolean success = absoluteError < properties.getSuccessToleranceMeters() && !tracker.emergencyBrake;
        String reason = success ? "STOP_SUCCESS"
            : overrun ? "STOP_OVERRUN"
            : tracker.emergencyBrake ? "EMERGENCY_BRAKE_USED" : "STOP_ERROR_EXCEEDED";
        String platformId = station.platformIds().isEmpty() ? null : station.platformIds().get(0);
        String resultId = "%s:%s:%s:%d".formatted(
            context.simulationRunId(), train.id(), station.id(), tracker.arrivalStartedAtTick);
        return new TrainStopResult(
            resultId, context.simulationRunId(), context.tick(), train.id(), station.id(), platformId,
            targetSource, targetPosition, train.positionMeters(), signedError, absoluteError,
            overrun, success, reason, tracker.maxDeceleration, tracker.maxJerk,
            tracker.brakeTransitions, tracker.emergencyBrake, train.operationMode(),
            properties.getParameterVersion(), context.simulatedTime());
    }

    private OperationalLineData.StationDefinition station(String stationId) {
        return stations.stream().filter(item -> item.id().equals(stationId)).findFirst().orElse(null);
    }

    private boolean isStoppingEpisode(TrainState train) {
        return "STATION_BRAKE".equals(train.dynamicsState())
            || "MA_BRAKE".equals(train.dynamicsState())
            || "STATION_STOPPED".equals(train.dynamicsState());
    }

    private void persist(TrainStopResult result) {
        try {
            jdbcTemplate.update("""
                INSERT INTO train_stop_result (
                  result_id, simulation_run_id, train_id, station_id, platform_id,
                  target_source, target_position_meters, actual_position_meters,
                  signed_error_meters, absolute_error_meters, overrun, success, reason_code,
                  maximum_deceleration_mps2, maximum_jerk_mps3, brake_transition_count,
                  emergency_brake, control_mode, parameter_version, stable_at_tick, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                result.resultId(), result.simulationRunId(), result.trainId(), result.stationId(),
                result.platformId(), result.targetSource(), result.targetPositionMeters(),
                result.actualPositionMeters(), result.signedErrorMeters(), result.absoluteErrorMeters(),
                result.overrun(), result.success(), result.reasonCode(),
                result.maximumDecelerationMetersPerSecondSquared(),
                result.maximumJerkMetersPerSecondCubed(), result.brakeTransitionCount(),
                result.emergencyBrake(), result.controlMode(), result.parameterVersion(),
                result.stableAtTick(), Timestamp.from(result.recordedAt()));
        } catch (DataAccessException ex) {
            log.warn("Train stop result persistence failed: {}", ex.getMessage());
        }
    }

    private static final class StopTracker {
        private boolean trackingEpisode;
        private String stationId;
        private long arrivalStartedAtTick;
        private boolean recorded;
        private double lastAcceleration;
        private boolean hasAcceleration;
        private double maxDeceleration;
        private double maxJerk;
        private String lastBrakeState;
        private int brakeTransitions;
        private boolean emergencyBrake;
        private double movementAuthorityTargetMeters = Double.NaN;

        void beginEpisode(long tick) {
            reset();
            trackingEpisode = true;
            arrivalStartedAtTick = tick;
        }

        void observe(TrainState train, double deltaSeconds) {
            double acceleration = train.accelerationMetersPerSecondSquared();
            maxDeceleration = Math.max(maxDeceleration, Math.max(0, -acceleration));
            if (hasAcceleration && deltaSeconds > 0) {
                maxJerk = Math.max(maxJerk, Math.abs(acceleration - lastAcceleration) / deltaSeconds);
            }
            lastAcceleration = acceleration;
            hasAcceleration = true;
            if (lastBrakeState != null && !lastBrakeState.equals(train.brakeState())) {
                brakeTransitions++;
            }
            lastBrakeState = train.brakeState();
            emergencyBrake |= "EMERGENCY".equals(train.brakeState())
                || "EMERGENCY_BRAKE".equals(train.status());
            if ("MA_BRAKE".equals(train.dynamicsState())
                || "MA_DISTANCE_LIMIT".equals(train.dynamicsConstraintReason())) {
                double target = train.positionMeters() + train.movementAuthorityDistanceMeters();
                movementAuthorityTargetMeters = Double.isFinite(movementAuthorityTargetMeters)
                    ? Math.min(movementAuthorityTargetMeters, target) : target;
            }
        }

        void enterStation(String stationId, long tick) {
            if (this.stationId != null && !this.stationId.equals(stationId)) {
                beginEpisode(tick);
            } else if (!trackingEpisode) {
                beginEpisode(tick);
            }
            this.stationId = stationId;
            this.recorded = false;
        }

        void leaveStation() {
            reset();
        }

        private void reset() {
            trackingEpisode = false;
            stationId = null;
            arrivalStartedAtTick = 0;
            recorded = false;
            lastAcceleration = 0;
            hasAcceleration = false;
            maxDeceleration = 0;
            maxJerk = 0;
            lastBrakeState = null;
            brakeTransitions = 0;
            emergencyBrake = false;
            movementAuthorityTargetMeters = Double.NaN;
        }
    }
}
