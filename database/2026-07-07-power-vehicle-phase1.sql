-- Power and vehicle phase 1 database change log.
-- Purpose: keep database-side documentation for the first internal implementation stage.

-- power_section_config:
--   Add substation_id, feeder_id, breaker_status, isolator_status, supply_mode,
--   maintenance_state, and lockout_state to preserve the static power topology
--   and simulated operation state used by PowerService.

-- train_physics_snapshot:
--   Add regen_brake_force_n and data_quality for vehicle physics diagnostics,
--   regenerative braking verification, and FMU fallback evidence.

-- fmu_fault_log:
--   Add tick and idx_fmu_fault_tick_train so FMU failure/fallback logs can be
--   aligned with simulation snapshots.

-- power_section_record:
--   Add load_w, available_power_w, absorbed_regen_power_w,
--   unabsorbed_regen_power_w, breaker_status, protection_state,
--   maintenance_state, lockout_state, affected_train_ids_json, and
--   data_quality for power-vehicle coupling replay.

-- New tables:
--   power_fault_record: power fault injection, isolation, recovery, and scope.
--   power_operation_log: simulated power switching and lockout operation audit.
--   power_maintenance_lock_record: maintenance lockout and grounding states.
--   train_fault_record: TCMS self-check, fault level, and degraded-mode records.

-- Runtime mapping:
--   SimulationPersistenceService writes train_physics_snapshot,
--   train_energy_record, and power_section_record on railway.simulation
--   persistence-step-millis boundaries.

-- The canonical create-table definitions are maintained in database/schema.sql.
