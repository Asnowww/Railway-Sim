CREATE TABLE IF NOT EXISTS simulation_run (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id VARCHAR(64) NOT NULL UNIQUE,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  started_at TIMESTAMP NULL,
  ended_at TIMESTAMP NULL,
  last_tick BIGINT NOT NULL DEFAULT 0,
  end_reason VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS vehicle_control_command_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  simulation_run_id VARCHAR(64) NOT NULL,
  tick BIGINT NOT NULL,
  train_id VARCHAR(64) NOT NULL,
  command_id VARCHAR(128),
  trace_id VARCHAR(128),
  operation_mode VARCHAR(32),
  decision_source VARCHAR(64),
  traction_command DOUBLE NOT NULL,
  brake_command DOUBLE NOT NULL,
  emergency_brake BOOLEAN NOT NULL,
  reason_code VARCHAR(128),
  decided_at TIMESTAMP NOT NULL,
  UNIQUE (simulation_run_id, tick, train_id)
);

CREATE TABLE IF NOT EXISTS alarm_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  alarm_id VARCHAR(255) NOT NULL UNIQUE,
  simulation_run_id VARCHAR(64) NOT NULL,
  alarm_code VARCHAR(192) NOT NULL,
  source_module VARCHAR(64) NOT NULL,
  location_ref VARCHAR(128) NOT NULL,
  level TINYINT NOT NULL,
  title VARCHAR(128) NOT NULL,
  detail_text VARCHAR(512) NOT NULL,
  state VARCHAR(32) NOT NULL,
  confirmed BOOLEAN NOT NULL,
  raised_at TIMESTAMP NOT NULL,
  last_seen_at TIMESTAMP NOT NULL,
  acknowledged_at TIMESTAMP NULL,
  acknowledged_by VARCHAR(64),
  cleared_at TIMESTAMP NULL,
  affected_train_ids_json JSON,
  affected_section_ids_json JSON,
  safety_action VARCHAR(128),
  clear_condition VARCHAR(255),
  recovery_condition VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS service_health_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  service_id VARCHAR(64) NOT NULL UNIQUE,
  state VARCHAR(32) NOT NULL,
  data_quality VARCHAR(32) NOT NULL,
  source_timestamp TIMESTAMP NULL,
  observed_at TIMESTAMP NOT NULL,
  simulation_run_id VARCHAR(64),
  last_accepted_tick BIGINT NOT NULL,
  topology_hash VARCHAR(128),
  config_hash VARCHAR(128),
  model_version VARCHAR(128),
  parameter_version VARCHAR(128),
  reason_text VARCHAR(512),
  recovery_gate_json JSON
);

CREATE TABLE IF NOT EXISTS service_health_baseline (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  service_id VARCHAR(64) NOT NULL UNIQUE,
  simulation_run_id VARCHAR(64),
  last_accepted_tick BIGINT NOT NULL,
  topology_hash VARCHAR(128) NOT NULL,
  config_hash VARCHAR(128) NOT NULL,
  model_version VARCHAR(128) NOT NULL,
  parameter_version VARCHAR(128) NOT NULL,
  source_timestamp TIMESTAMP NULL
);
