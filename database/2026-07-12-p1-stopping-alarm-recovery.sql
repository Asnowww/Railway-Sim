-- P1-2/P1-3 incremental migration for databases already initialized from ea83279.
-- Execute once before deploying the matching backend build.

ALTER TABLE train_stop_result
  ADD COLUMN target_valid_from_tick BIGINT NOT NULL DEFAULT 0 AFTER target_position_meters,
  ADD COLUMN target_overridden_by_ma BOOLEAN NOT NULL DEFAULT FALSE AFTER target_valid_from_tick,
  ADD COLUMN final_control_stage VARCHAR(32) NOT NULL DEFAULT 'HOLD' AFTER emergency_brake,
  ADD COLUMN control_stage_history_json JSON NULL AFTER final_control_stage;

UPDATE train_stop_result
   SET control_stage_history_json = JSON_ARRAY(final_control_stage)
 WHERE control_stage_history_json IS NULL;

ALTER TABLE train_stop_result
  MODIFY COLUMN control_stage_history_json JSON NOT NULL;

ALTER TABLE alarm_record
  ADD COLUMN affected_train_ids_json JSON NULL AFTER cleared_at,
  ADD COLUMN affected_section_ids_json JSON NULL AFTER affected_train_ids_json,
  ADD COLUMN safety_action VARCHAR(128) NOT NULL DEFAULT 'MONITOR' AFTER affected_section_ids_json,
  ADD COLUMN clear_condition VARCHAR(255) NOT NULL DEFAULT 'SOURCE_CONDITION_CLEARED' AFTER safety_action,
  ADD COLUMN recovery_condition VARCHAR(255) NOT NULL DEFAULT 'STATE_RECONCILED' AFTER clear_condition;

CREATE TABLE IF NOT EXISTS service_health_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  service_id VARCHAR(64) NOT NULL UNIQUE,
  state VARCHAR(32) NOT NULL,
  data_quality VARCHAR(32) NOT NULL,
  source_timestamp TIMESTAMP NULL,
  observed_at TIMESTAMP NOT NULL,
  simulation_run_id VARCHAR(64),
  last_accepted_tick BIGINT NOT NULL DEFAULT -1,
  topology_hash VARCHAR(128),
  config_hash VARCHAR(128),
  model_version VARCHAR(128),
  parameter_version VARCHAR(128),
  reason_text VARCHAR(512),
  recovery_gate_json JSON,
  INDEX idx_service_health_state (state, observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS service_health_baseline (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  service_id VARCHAR(64) NOT NULL UNIQUE,
  simulation_run_id VARCHAR(64),
  last_accepted_tick BIGINT NOT NULL DEFAULT -1,
  topology_hash VARCHAR(128) NOT NULL,
  config_hash VARCHAR(128) NOT NULL,
  model_version VARCHAR(128) NOT NULL,
  parameter_version VARCHAR(128) NOT NULL,
  source_timestamp TIMESTAMP NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
