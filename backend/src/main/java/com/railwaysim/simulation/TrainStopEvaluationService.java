package com.railwaysim.simulation;

import com.railwaysim.config.StoppingControlProperties;
import com.railwaysim.infrastructure.OperationalLineData;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.simulation.event.SimpleEventBus;
import com.railwaysim.simulation.event.StoppingTargetOverriddenEvent;
import com.railwaysim.train.TrainState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TrainStopEvaluationService {
    private static final Logger log = LoggerFactory.getLogger(TrainStopEvaluationService.class);

    private final List<OperationalLineData.StationDefinition> stations;
    private final OperationalLineData lineData;
    private final JdbcTemplate jdbcTemplate;
    private final StoppingControlProperties properties;
    private final SimpleEventBus eventBus;
    private final Map<String, StopTracker> trackers = new LinkedHashMap<>();
    private final List<TrainStopResult> results = new ArrayList<>();

    @Autowired
    public TrainStopEvaluationService(StaticInfrastructureCatalog catalog, JdbcTemplate jdbcTemplate,
        StoppingControlProperties properties, SimpleEventBus eventBus) {
        this.stations = catalog.lineData().stations();
        this.lineData = catalog.lineData();
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.eventBus = eventBus;
    }

    TrainStopEvaluationService(StaticInfrastructureCatalog catalog, JdbcTemplate jdbcTemplate,
        StoppingControlProperties properties) {
        this(catalog, jdbcTemplate, properties, new SimpleEventBus());
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
                tracker.observe(context, train, eventBus, properties);
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
        String platformId = station.platformIds().isEmpty() ? null : station.platformIds().get(0);
        StoppingTarget target = tracker.target == null
            ? new StoppingTarget("STATION", nearestStopPointMeters(station, train.positionMeters()),
                station.id(), platformId, tracker.arrivalStartedAtTick)
            : new StoppingTarget(
                tracker.target.source(), tracker.target.positionMeters(), station.id(), platformId,
                tracker.target.validFromTick());
        double targetPosition = target.positionMeters();
        double signedError = train.positionMeters() - targetPosition;
        double absoluteError = Math.abs(signedError);
        boolean overrun = signedError > properties.getOverrunThresholdMeters();
        if (overrun) {
            tracker.controlStages.add("OVERRUN");
        }
        boolean success = absoluteError < properties.getSuccessToleranceMeters() && !tracker.emergencyBrake;
        String reason = success ? "STOP_SUCCESS"
            : overrun ? "STOP_OVERRUN"
            : tracker.emergencyBrake ? "EMERGENCY_BRAKE_USED" : "STOP_ERROR_EXCEEDED";
        String resultId = "%s:%s:%s:%d".formatted(
            context.simulationRunId(), train.id(), station.id(), tracker.arrivalStartedAtTick);
        return new TrainStopResult(
            resultId, context.simulationRunId(), context.tick(), train.id(), station.id(), platformId,
            target.source(), targetPosition, target.validFromTick(), tracker.targetOverriddenByMa,
            train.positionMeters(), signedError, absoluteError,
            overrun, success, reason, tracker.maxDeceleration, tracker.maxJerk,
            tracker.brakeTransitions, tracker.emergencyBrake, overrun ? "OVERRUN" : "HOLD",
            List.copyOf(tracker.controlStages), train.operationMode(),
            properties.getParameterVersion(), context.simulatedTime());
    }

    /**
     * 停准评估目标：该站各股道站台停车点(stop_right)中离实际停车位最近者。
     * 列车按本股道停车点停靠，最近者即本股道目标；无站台数据回退站中心。
     */
    private double nearestStopPointMeters(OperationalLineData.StationDefinition station, double stoppedAtMeters) {
        return lineData.platforms().stream()
            .filter(platform -> station.platformIds().contains(platform.id()))
            .filter(platform -> platform.stopRightMeters() > platform.stopLeftMeters())
            .mapToDouble(OperationalLineData.PlatformDefinition::stopRightMeters)
            .boxed()
            .min(java.util.Comparator.comparingDouble(stop -> Math.abs(stop - stoppedAtMeters)))
            .orElse(station.centerMeters());
    }

    private OperationalLineData.StationDefinition station(String stationId) {
        return stations.stream().filter(item -> item.id().equals(stationId)).findFirst().orElse(null);
    }

    private boolean isStoppingEpisode(TrainState train) {
        return "STATION_BRAKE".equals(train.dynamicsState())
            || "MA_BRAKE".equals(train.dynamicsState())
            || "STATION_STOPPED".equals(train.dynamicsState())
            || (Double.isFinite(train.stationDistanceMeters())
                && train.stationDistanceMeters() >= 0
                && train.stationDistanceMeters() < 1_000_000
                && train.stationDistanceMeters()
                    <= train.stoppingDistanceMeters()
                        + properties.getMaximumApproachBufferMeters() * 2);
    }

    private void persist(TrainStopResult result) {
        try {
            jdbcTemplate.update("""
                INSERT INTO train_stop_result (
                  result_id, simulation_run_id, train_id, station_id, platform_id,
                  target_source, target_position_meters, target_valid_from_tick,
                  target_overridden_by_ma, actual_position_meters,
                  signed_error_meters, absolute_error_meters, overrun, success, reason_code,
                  maximum_deceleration_mps2, maximum_jerk_mps3, brake_transition_count,
                  emergency_brake, final_control_stage, control_stage_history_json,
                  control_mode, parameter_version,
                  stable_at_tick, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                result.resultId(), result.simulationRunId(), result.trainId(), result.stationId(),
                result.platformId(), result.targetSource(), result.targetPositionMeters(),
                result.targetValidFromTick(), result.targetOverriddenByMovementAuthority(),
                result.actualPositionMeters(), result.signedErrorMeters(), result.absoluteErrorMeters(),
                result.overrun(), result.success(), result.reasonCode(),
                result.maximumDecelerationMetersPerSecondSquared(),
                result.maximumJerkMetersPerSecondCubed(), result.brakeTransitionCount(),
                result.emergencyBrake(), result.finalControlStage(),
                stageHistoryJson(result.controlStageHistory()), result.controlMode(),
                result.parameterVersion(),
                result.stableAtTick(), Timestamp.from(result.recordedAt()));
        } catch (DataAccessException ex) {
            log.warn("Train stop result persistence failed: {}", ex.getMessage());
        }
    }

    private String stageHistoryJson(List<String> stages) {
        return stages.stream().map(stage -> "\"" + stage + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
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
        private StoppingTarget target;
        private boolean targetOverriddenByMa;
        private final LinkedHashSet<String> controlStages = new LinkedHashSet<>();

        void beginEpisode(long tick) {
            reset();
            trackingEpisode = true;
            arrivalStartedAtTick = tick;
        }

        void observe(
            TickContext context, TrainState train, SimpleEventBus eventBus,
            StoppingControlProperties properties
        ) {
            double deltaSeconds = context.deltaSeconds();
            controlStages.add(controlStage(train, properties));
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
            if ("STATION_BRAKE".equals(train.dynamicsState())
                && Double.isFinite(train.stationDistanceMeters())) {
                double stationTargetPosition = train.positionMeters() + train.stationDistanceMeters();
                if (target == null || "STATION".equals(target.source())) {
                    target = new StoppingTarget(
                        "STATION", stationTargetPosition, null, null, context.tick());
                }
            }
            if (("MA_BRAKE".equals(train.dynamicsState())
                || "MA_DISTANCE_LIMIT".equals(train.dynamicsConstraintReason()))
                && Double.isFinite(train.movementAuthorityDistanceMeters())) {
                double maTargetPosition = train.positionMeters() + train.movementAuthorityDistanceMeters();
                StoppingTarget maTarget = new StoppingTarget(
                    "MOVEMENT_AUTHORITY", maTargetPosition, null, null, context.tick());
                boolean replacesStation = target == null || "STATION".equals(target.source());
                if (replacesStation && (target == null || maTargetPosition < target.positionMeters())) {
                    StoppingTarget previousTarget = target;
                    target = maTarget;
                    targetOverriddenByMa = true;
                    eventBus.publish(new StoppingTargetOverriddenEvent(
                        context.simulationRunId(), context.tick(), train.id(), previousTarget,
                        maTarget, "TARGET_OVERRIDDEN_BY_MA", context.simulatedTime()));
                } else if ("MOVEMENT_AUTHORITY".equals(target.source())
                    && maTargetPosition < target.positionMeters()) {
                    target = maTarget;
                }
            }
        }

        private String controlStage(TrainState train, StoppingControlProperties properties) {
            if ("STATION_STOPPED".equals(train.dynamicsState())) return "HOLD";
            if (!"STATION_BRAKE".equals(train.dynamicsState())
                && !"MA_BRAKE".equals(train.dynamicsState())) return "APPROACH";
            if (train.stationDistanceMeters() <= properties.getStationStopWindowMeters() * 2) {
                return "FINE_STOP";
            }
            if (train.stationDistanceMeters() <= train.stoppingDistanceMeters()) return "BRAKE_2";
            return "BRAKE_1";
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
            target = null;
            targetOverriddenByMa = false;
            controlStages.clear();
        }
    }
}
