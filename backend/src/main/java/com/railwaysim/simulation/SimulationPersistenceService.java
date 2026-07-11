package com.railwaysim.simulation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.railwaysim.power.PowerSectionState;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.simulation.event.DomainEvent;
import com.railwaysim.simulation.event.FmuFallbackActivatedEvent;
import com.railwaysim.simulation.event.FmuStepFailedEvent;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.control.VehicleControlDecision;
import com.railwaysim.vehicle.control.VehicleControlDecisionRepository;
import java.sql.Timestamp;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SimulationPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(SimulationPersistenceService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final VehicleControlDecisionRepository decisionRepository;
    private boolean persistenceWarningLogged;

    public SimulationPersistenceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
        VehicleControlDecisionRepository decisionRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.decisionRepository = decisionRepository;
    }

    public void persistVehicleControlDecisions(TickContext context) {
        try {
            for (VehicleControlDecision decision : decisionRepository.byRunAndTick(
                context.simulationRunId(), context.tick())) {
                jdbcTemplate.update("""
                    INSERT INTO vehicle_control_command_log (
                      simulation_run_id, tick, train_id, command_id, trace_id,
                      operation_mode, decision_source, traction_command, brake_command,
                      emergency_brake, reason_code, decided_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    decision.runId(), decision.tick(), decision.trainId(), decision.decisionId(),
                    decision.traceId(), decision.mode().name(), decision.source(),
                    decision.tractionCommand(), decision.brakeCommand(), decision.emergencyBrake(),
                    decision.selectedReasonCode(), Timestamp.from(decision.decidedAt()));
            }
        } catch (DataAccessException ex) {
            log.warn("Vehicle control decision persistence failed: {}", ex.getMessage());
        }
    }

    public void persist(
        TickContext context,
        List<TrainState> trainStates,
        List<PowerSectionState> powerSectionStates
    ) {
        persist(context, trainStates, powerSectionStates, List.of());
    }

    public void persist(
        TickContext context,
        List<TrainState> trainStates,
        List<PowerSectionState> powerSectionStates,
        List<DomainEvent> events
    ) {
        try {
            for (TrainState train : trainStates) {
                persistTrainPhysics(context, train);
                persistTrainEnergy(context, train);
            }
            for (PowerSectionState section : powerSectionStates) {
                persistPowerSection(context, section);
            }
            for (DomainEvent event : events) {
                persistEvent(context, event);
            }
        } catch (DataAccessException ex) {
            if (!persistenceWarningLogged) {
                log.warn("Simulation persistence skipped after database write failure: {}", ex.getMessage());
                persistenceWarningLogged = true;
            }
        }
    }

    private void persistTrainPhysics(TickContext context, TrainState train) {
        jdbcTemplate.update(
            """
                INSERT INTO train_physics_snapshot (
                  simulation_run_id, train_id, tick, control_session_state, signal_network_status,
                  power_network_status, control_session_reason, link_id, direction,
                  position_meters, speed_mps, acceleration_mps2,
                  traction_force_n, brake_force_n, regen_brake_force_n, rail_current_a,
                  traction_power_w, regen_power_w, fault_code, data_quality,
                  load_mass_kg, overload_status, available_traction_count,
                  available_brake_count, vehicle_protection_reason,
                  dynamics_state, dynamics_constraint_reason, speed_limit_mps,
                  vehicle_fault_speed_limit_mps, ma_distance_meters,
                  station_distance_meters, stopping_distance_meters,
                  recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            context.simulationRunId(),
            train.id(),
            context.tick(),
            train.controlSessionState(),
            train.signalNetworkStatus(),
            train.powerNetworkStatus(),
            train.controlSessionReason(),
            train.linkId(),
            train.direction(),
            train.positionMeters(),
            train.speedMetersPerSecond(),
            train.accelerationMetersPerSecondSquared(),
            train.tractionForceNewtons(),
            train.brakeForceNewtons(),
            train.regenBrakeForceNewtons(),
            train.railCurrentAmps(),
            train.tractionPowerWatts(),
            train.regenPowerWatts(),
            train.faultCode(),
            train.dataQuality(),
            train.loadMassKg(),
            train.overloadStatus(),
            train.availableTractionCount(),
            train.availableBrakeCount(),
            train.vehicleProtectionReason(),
            train.dynamicsState(),
            train.dynamicsConstraintReason(),
            train.speedLimitMetersPerSecond(),
            train.vehicleFaultSpeedLimitMetersPerSecond(),
            train.movementAuthorityDistanceMeters(),
            train.stationDistanceMeters(),
            train.stoppingDistanceMeters(),
            timestamp(context)
        );
    }

    private void persistTrainEnergy(TickContext context, TrainState train) {
        jdbcTemplate.update(
            """
                INSERT INTO train_energy_record (
                  simulation_run_id, train_id, tick, energy_consumed_kwh, energy_regenerated_kwh, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
            context.simulationRunId(),
            train.id(),
            context.tick(),
            train.energyConsumedKwh(),
            train.energyRegeneratedKwh(),
            timestamp(context)
        );
    }

    private void persistPowerSection(TickContext context, PowerSectionState section) {
        jdbcTemplate.update(
            """
                INSERT INTO power_section_record (
                  simulation_run_id, section_id, tick, voltage, current_value, status, load_w, available_power_w,
                  regen_power_w, absorbed_regen_power_w, unabsorbed_regen_power_w,
                  breaker_status, protection_state, maintenance_state, lockout_state,
                  affected_train_ids_json, data_quality, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            context.simulationRunId(),
            section.id(),
            context.tick(),
            section.voltage(),
            section.current(),
            section.status(),
            section.loadWatts(),
            section.availablePowerWatts(),
            section.regenPowerWatts(),
            section.absorbedRegenPowerWatts(),
            section.unabsorbedRegenPowerWatts(),
            section.breakerStatus(),
            section.protectionState(),
            section.maintenanceState(),
            section.lockoutState(),
            toJson(section.affectedTrainIds()),
            section.dataQuality(),
            timestamp(context)
        );
    }

    private void persistEvent(TickContext context, DomainEvent event) {
        if (event instanceof FmuStepFailedEvent fmuStepFailed) {
            persistFmuFault(
                context,
                fmuStepFailed.trainId(),
                "FMU_STEP_FAILED",
                fmuStepFailed.detail(),
                false,
                fmuStepFailed.occurredAt()
            );
            return;
        }
        if (event instanceof FmuFallbackActivatedEvent fallbackActivated) {
            persistFmuFault(
                context,
                null,
                "FMU_FALLBACK_ACTIVATED",
                fallbackActivated.detail(),
                true,
                fallbackActivated.occurredAt()
            );
        }
    }

    private void persistFmuFault(
        TickContext context,
        String trainId,
        String faultCode,
        String detail,
        boolean fallbackActivated,
        java.time.Instant occurredAt
    ) {
        jdbcTemplate.update(
            """
                INSERT INTO fmu_fault_log (
                  simulation_run_id, tick, train_id, fault_code, detail_text, fallback_activated, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
            context.simulationRunId(),
            context.tick(),
            trainId,
            faultCode,
            detail,
            fallbackActivated,
            Timestamp.from(occurredAt == null ? context.simulatedTime() : occurredAt)
        );
    }

    // ==================== 信号轨道持久化 ====================

    public void persistTrackOccupancy(TickContext context, List<TrackSegmentState> segments) {
        try {
            for (TrackSegmentState seg : segments) {
                if (seg.occupancy() == com.railwaysim.track.TrackOccupancy.FREE) {
                    continue; // 只记录非空闲状态
                }
                jdbcTemplate.update(
                    """
                        INSERT INTO track_occupancy_record (
                          simulation_run_id, segment_id, occupancy, recorded_at
                        ) VALUES (?, ?, ?, ?)
                        """,
                    context.simulationRunId(),
                    seg.id(),
                    seg.occupancy().name(),
                    timestamp(context)
                );
            }
        } catch (DataAccessException ex) {
            log.warn("Track occupancy persist failed: {}", ex.getMessage());
        }
    }

    public void persistSignalStates(TickContext context, List<MovementAuthority> authorities) {
        try {
            for (MovementAuthority ma : authorities) {
                jdbcTemplate.update(
                    """
                        INSERT INTO signal_state_record (
                          simulation_run_id, train_id, authority_end_meters, speed_limit_mps, reason, recorded_at
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """,
                    context.simulationRunId(),
                    ma.trainId(),
                    ma.authorityEndMeters(),
                    ma.speedLimitMetersPerSecond(),
                    ma.reason(),
                    timestamp(context)
                );
            }
        } catch (DataAccessException ex) {
            log.warn("Signal state persist failed: {}", ex.getMessage());
        }
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize affected train ids, falling back to empty JSON array", ex);
            return "[]";
        }
    }

    private Timestamp timestamp(TickContext context) {
        return Timestamp.from(context.simulatedTime());
    }
}
