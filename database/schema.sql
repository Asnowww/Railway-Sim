CREATE TABLE IF NOT EXISTS line_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  line_id VARCHAR(64) NOT NULL UNIQUE,
  line_name VARCHAR(128) NOT NULL,
  length_meters DOUBLE NOT NULL,
  default_speed_limit_mps DOUBLE NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS station_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  station_id VARCHAR(64) NOT NULL UNIQUE,
  line_id VARCHAR(64) NOT NULL,
  station_name VARCHAR(128) NOT NULL,
  position_meters DOUBLE NOT NULL,
  platform_capacity INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_station_config_line_position (line_id, position_meters)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS track_segment_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  segment_id VARCHAR(64) NOT NULL UNIQUE,
  line_id VARCHAR(64) NOT NULL,
  from_node VARCHAR(64) NOT NULL,
  to_node VARCHAR(64) NOT NULL,
  start_meters DOUBLE NOT NULL,
  end_meters DOUBLE NOT NULL,
  speed_limit_mps DOUBLE NOT NULL,
  gradient_permille DOUBLE NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_track_segment_line_range (line_id, start_meters, end_meters)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS switch_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  switch_id VARCHAR(64) NOT NULL UNIQUE,
  line_id VARCHAR(64) NOT NULL,
  node_id VARCHAR(64) NOT NULL,
  normal_target VARCHAR(64) NOT NULL,
  reverse_target VARCHAR(64) NOT NULL,
  default_position VARCHAR(32) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS point_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  point_id VARCHAR(64) NOT NULL UNIQUE,
  line_id VARCHAR(64) NOT NULL,
  point_name VARCHAR(128),
  track_name VARCHAR(128),
  kilometer_mark_meters DOUBLE NOT NULL,
  direction_code VARCHAR(32),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_point_config_line_position (line_id, kilometer_mark_meters)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS track_segment_topology_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  segment_id VARCHAR(64) NOT NULL UNIQUE,
  line_id VARCHAR(64) NOT NULL,
  raw_segment_id INT NOT NULL DEFAULT 0,
  length_meters DOUBLE NOT NULL,
  start_endpoint_type INT NOT NULL DEFAULT 0,
  start_endpoint_id INT NOT NULL DEFAULT 0,
  end_endpoint_type INT NOT NULL DEFAULT 0,
  end_endpoint_id INT NOT NULL DEFAULT 0,
  forward_neighbor_ids_json JSON,
  side_neighbor_ids_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_track_segment_topology_line_raw (line_id, raw_segment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS speed_limit_zone_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  zone_id VARCHAR(64) NOT NULL UNIQUE,
  line_id VARCHAR(64) NOT NULL,
  segment_id VARCHAR(64) NOT NULL,
  start_meters DOUBLE NOT NULL,
  end_meters DOUBLE NOT NULL,
  speed_limit_mps DOUBLE NOT NULL,
  switch_id VARCHAR(64),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_speed_limit_line_range (line_id, start_meters, end_meters)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS gradient_zone_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  zone_id VARCHAR(64) NOT NULL UNIQUE,
  line_id VARCHAR(64) NOT NULL,
  start_meters DOUBLE NOT NULL,
  end_meters DOUBLE NOT NULL,
  gradient DOUBLE NOT NULL,
  raw_permille_value INT NOT NULL DEFAULT 0,
  direction_code VARCHAR(32),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_gradient_line_range (line_id, start_meters, end_meters)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS platform_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  platform_id VARCHAR(64) NOT NULL UNIQUE,
  line_id VARCHAR(64) NOT NULL,
  center_meters DOUBLE NOT NULL,
  anchor_segment_id VARCHAR(64),
  direction_code VARCHAR(32),
  raw_center_mark VARCHAR(64),
  interoperability_id VARCHAR(128),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_platform_line_position (line_id, center_meters)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS station_platform_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  station_id VARCHAR(64) NOT NULL,
  platform_id VARCHAR(64) NOT NULL,
  line_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_station_platform (station_id, platform_id),
  INDEX idx_station_platform_line_station (line_id, station_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS switch_detail_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  switch_id VARCHAR(64) NOT NULL UNIQUE,
  line_id VARCHAR(64) NOT NULL,
  switch_name VARCHAR(128),
  linked_switch_id VARCHAR(64),
  direction_code VARCHAR(32),
  merge_segment_id VARCHAR(64),
  diverging_speed_limit_mps DOUBLE NOT NULL DEFAULT 0,
  interoperability_id VARCHAR(128),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS signal_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  signal_id VARCHAR(64) NOT NULL UNIQUE,
  line_id VARCHAR(64) NOT NULL,
  signal_name VARCHAR(128),
  type_code VARCHAR(32),
  attribute_code VARCHAR(32),
  segment_id VARCHAR(64),
  position_meters DOUBLE NOT NULL,
  protection_direction_code VARCHAR(32),
  lamp_info_code VARCHAR(64),
  interoperability_id VARCHAR(128),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_signal_line_position (line_id, position_meters)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS balise_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  balise_id VARCHAR(64) NOT NULL UNIQUE,
  line_id VARCHAR(64) NOT NULL,
  hex_id VARCHAR(64),
  balise_name VARCHAR(128),
  segment_id VARCHAR(64),
  position_meters DOUBLE NOT NULL,
  interoperability_id VARCHAR(128),
  attribute_code VARCHAR(32),
  linked_signal_id VARCHAR(64),
  direction_code VARCHAR(32),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_balise_line_position (line_id, position_meters)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS route_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  route_id VARCHAR(64) NOT NULL UNIQUE,
  line_id VARCHAR(64) NOT NULL,
  route_name VARCHAR(128),
  type_code VARCHAR(32),
  start_signal_id VARCHAR(64),
  end_signal_id VARCHAR(64),
  axle_section_ids_json JSON,
  protection_section_ids_json JSON,
  point_approach_section_ids_json JSON,
  cbtc_approach_section_ids_json JSON,
  point_trigger_section_ids_json JSON,
  cbtc_trigger_section_ids_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_route_line_signals (line_id, start_signal_id, end_signal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS power_section_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  section_id VARCHAR(64) NOT NULL UNIQUE,
  line_id VARCHAR(64) NOT NULL,
  section_name VARCHAR(128) NOT NULL,
  substation_id VARCHAR(64),
  feeder_id VARCHAR(64),
  start_meters DOUBLE NOT NULL,
  end_meters DOUBLE NOT NULL,
  nominal_voltage DOUBLE NOT NULL DEFAULT 1500,
  breaker_status VARCHAR(32) NOT NULL DEFAULT 'CLOSED',
  isolator_status VARCHAR(32) NOT NULL DEFAULT 'CLOSED',
  supply_mode VARCHAR(32) NOT NULL DEFAULT 'DOUBLE_END',
  maintenance_state VARCHAR(32) NOT NULL DEFAULT 'NONE',
  lockout_state VARCHAR(32) NOT NULL DEFAULT 'UNLOCKED',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_power_section_line_range (line_id, start_meters, end_meters)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS train_info (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  train_id VARCHAR(64) NOT NULL UNIQUE,
  route_id VARCHAR(64) NOT NULL,
  train_length_meters DOUBLE NOT NULL DEFAULT 120,
  max_speed_mps DOUBLE NOT NULL DEFAULT 22.2,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS train_run_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  train_id VARCHAR(64) NOT NULL,
  route_id VARCHAR(64) NOT NULL,
  position_meters DOUBLE NOT NULL,
  speed_mps DOUBLE NOT NULL,
  load_rate DOUBLE NOT NULL,
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_train_run_record_train_time (train_id, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS track_occupancy_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  simulation_run_id VARCHAR(64) NOT NULL,
  segment_id VARCHAR(64) NOT NULL,
  occupancy VARCHAR(32) NOT NULL,
  train_ids_json JSON,
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_track_occupancy_segment_time (segment_id, recorded_at),
  INDEX idx_track_occupancy_run_time (simulation_run_id, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS signal_state_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  simulation_run_id VARCHAR(64) NOT NULL,
  train_id VARCHAR(64) NOT NULL,
  authority_end_meters DOUBLE NOT NULL,
  speed_limit_mps DOUBLE NOT NULL,
  reason VARCHAR(255),
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_signal_state_train_time (train_id, recorded_at),
  INDEX idx_signal_state_run_time (simulation_run_id, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS power_state_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  section_id VARCHAR(64) NOT NULL,
  voltage DOUBLE NOT NULL,
  current_value DOUBLE NOT NULL,
  status VARCHAR(32) NOT NULL,
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_power_state_section_time (section_id, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS passenger_flow_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  station_id VARCHAR(64) NOT NULL,
  inbound_count INT NOT NULL DEFAULT 0,
  outbound_count INT NOT NULL DEFAULT 0,
  waiting_count INT NOT NULL DEFAULT 0,
  load_rate DOUBLE NOT NULL DEFAULT 0,
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_passenger_flow_station_time (station_id, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS alarm_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  alarm_id VARCHAR(255) NOT NULL UNIQUE,
  simulation_run_id VARCHAR(64) NOT NULL,
  alarm_code VARCHAR(192) NOT NULL,
  source_module VARCHAR(64) NOT NULL,
  location_ref VARCHAR(128) NOT NULL,
  level TINYINT NOT NULL,
  title VARCHAR(128) NOT NULL,
  detail_text VARCHAR(512) NOT NULL,
  state VARCHAR(32) NOT NULL DEFAULT 'RAISED',
  confirmed BOOLEAN NOT NULL DEFAULT FALSE,
  raised_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  acknowledged_at TIMESTAMP NULL,
  acknowledged_by VARCHAR(64),
  cleared_at TIMESTAMP NULL,
  INDEX idx_alarm_record_level_time (level, raised_at),
  INDEX idx_alarm_record_run_state (simulation_run_id, state, raised_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dispatch_command_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  command_id VARCHAR(64) NOT NULL,
  train_id VARCHAR(64),
  command_type VARCHAR(64) NOT NULL,
  payload_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_dispatch_command_status_time (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS system_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  config_key VARCHAR(128) NOT NULL UNIQUE,
  config_value VARCHAR(1024) NOT NULL,
  description VARCHAR(255),
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS operation_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  operator_name VARCHAR(64) NOT NULL,
  operation_type VARCHAR(64) NOT NULL,
  target_ref VARCHAR(128) NOT NULL,
  detail_json JSON,
  run_id VARCHAR(64) DEFAULT NULL,
  tick BIGINT DEFAULT NULL,
  trace_id VARCHAR(64) DEFAULT NULL,
  before_state VARCHAR(1024) DEFAULT NULL,
  after_state VARCHAR(1024) DEFAULT NULL,
  reason VARCHAR(512) DEFAULT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  error_text VARCHAR(1024) DEFAULT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_operation_log_type_time (operation_type, created_at),
  INDEX idx_operation_log_trace (trace_id),
  INDEX idx_operation_log_run_tick (run_id, tick)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS simulation_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id VARCHAR(64) NOT NULL UNIQUE,
  status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  started_at TIMESTAMP NULL,
  ended_at TIMESTAMP NULL,
  last_tick BIGINT NOT NULL DEFAULT 0,
  end_reason VARCHAR(255),
  scenario_name VARCHAR(128),
  config_hash VARCHAR(128),
  model_version VARCHAR(128),
  INDEX idx_simulation_run_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS protocol_packet_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  protocol_family VARCHAR(64) NOT NULL,
  adapter_id VARCHAR(128) NOT NULL,
  packet_direction VARCHAR(32) NOT NULL,
  byte_length INT NOT NULL DEFAULT 0,
  packet_summary VARCHAR(1000),
  process_status VARCHAR(32) NOT NULL,
  error_message VARCHAR(1000),
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_protocol_packet_log_adapter_time (adapter_id, recorded_at),
  INDEX idx_protocol_packet_log_family_time (protocol_family, recorded_at),
  INDEX idx_protocol_packet_log_status_time (process_status, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS train_physics_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  train_type VARCHAR(64) NOT NULL UNIQUE,
  empty_mass_kg DOUBLE NOT NULL,
  max_load_mass_kg DOUBLE NOT NULL,
  max_traction_power_w DOUBLE NOT NULL,
  max_traction_force_n DOUBLE NOT NULL,
  max_service_brake_force_n DOUBLE NOT NULL,
  max_emergency_brake_force_n DOUBLE NOT NULL,
  resistance_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS train_physics_snapshot (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  simulation_run_id VARCHAR(64) NOT NULL,
  train_id VARCHAR(64) NOT NULL,
  tick BIGINT NOT NULL,
  control_session_state VARCHAR(32) NOT NULL DEFAULT 'IN_SERVICE',
  signal_network_status VARCHAR(32) NOT NULL DEFAULT 'ATTACHED',
  power_network_status VARCHAR(32) NOT NULL DEFAULT 'ATTACHED',
  control_session_reason VARCHAR(128) NOT NULL DEFAULT 'EXTERNAL_CONTROL_IN_SERVICE',
  link_id INT NOT NULL DEFAULT 0,
  direction VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  position_meters DOUBLE NOT NULL,
  speed_mps DOUBLE NOT NULL,
  acceleration_mps2 DOUBLE NOT NULL,
  traction_force_n DOUBLE NOT NULL,
  brake_force_n DOUBLE NOT NULL,
  regen_brake_force_n DOUBLE NOT NULL DEFAULT 0,
  rail_current_a DOUBLE NOT NULL,
  traction_power_w DOUBLE NOT NULL,
  regen_power_w DOUBLE NOT NULL,
  fault_code VARCHAR(64) NOT NULL,
  data_quality VARCHAR(32) NOT NULL DEFAULT 'GOOD',
  load_mass_kg DOUBLE NOT NULL DEFAULT 0,
  overload_status VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
  available_traction_count INT NOT NULL DEFAULT 6,
  available_brake_count INT NOT NULL DEFAULT 6,
  vehicle_protection_reason VARCHAR(64) NOT NULL DEFAULT 'NONE',
  dynamics_state VARCHAR(32) NOT NULL DEFAULT 'COASTING',
  dynamics_constraint_reason VARCHAR(128) NOT NULL DEFAULT 'NONE',
  speed_limit_mps DOUBLE NOT NULL DEFAULT 0,
  vehicle_fault_speed_limit_mps DOUBLE NOT NULL DEFAULT 0,
  ma_distance_meters DOUBLE NOT NULL DEFAULT 0,
  station_distance_meters DOUBLE NOT NULL DEFAULT 0,
  stopping_distance_meters DOUBLE NOT NULL DEFAULT 0,
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_train_physics_snapshot_train_tick (train_id, tick),
  INDEX idx_train_physics_snapshot_run_tick_train (simulation_run_id, tick, train_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS train_energy_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  simulation_run_id VARCHAR(64) NOT NULL,
  train_id VARCHAR(64) NOT NULL,
  tick BIGINT NOT NULL,
  energy_consumed_kwh DOUBLE NOT NULL,
  energy_regenerated_kwh DOUBLE NOT NULL,
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_train_energy_record_train_tick (train_id, tick),
  INDEX idx_train_energy_record_run_tick_train (simulation_run_id, tick, train_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fmu_call_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tick BIGINT NOT NULL,
  service_url VARCHAR(255) NOT NULL,
  request_count INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  elapsed_millis BIGINT NOT NULL DEFAULT 0,
  detail_text VARCHAR(512),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_fmu_call_log_tick_status (tick, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fmu_fault_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  simulation_run_id VARCHAR(64) NOT NULL,
  tick BIGINT NOT NULL DEFAULT 0,
  train_id VARCHAR(64),
  fault_code VARCHAR(64) NOT NULL,
  detail_text VARCHAR(512) NOT NULL,
  fallback_activated BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_fmu_fault_tick_train (tick, train_id),
  INDEX idx_fmu_fault_run_tick (simulation_run_id, tick),
  INDEX idx_fmu_fault_log_train_time (train_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS power_section_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  simulation_run_id VARCHAR(64) NOT NULL,
  section_id VARCHAR(64) NOT NULL,
  tick BIGINT NOT NULL,
  voltage DOUBLE NOT NULL,
  current_value DOUBLE NOT NULL,
  status VARCHAR(32) NOT NULL,
  load_w DOUBLE NOT NULL DEFAULT 0,
  available_power_w DOUBLE NOT NULL DEFAULT 0,
  regen_power_w DOUBLE NOT NULL DEFAULT 0,
  absorbed_regen_power_w DOUBLE NOT NULL DEFAULT 0,
  unabsorbed_regen_power_w DOUBLE NOT NULL DEFAULT 0,
  breaker_status VARCHAR(32) NOT NULL DEFAULT 'CLOSED',
  protection_state VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
  maintenance_state VARCHAR(32) NOT NULL DEFAULT 'NONE',
  lockout_state VARCHAR(32) NOT NULL DEFAULT 'UNLOCKED',
  affected_train_ids_json JSON,
  data_quality VARCHAR(32) NOT NULL DEFAULT 'GOOD',
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_power_section_record_section_tick (section_id, tick),
  INDEX idx_power_section_record_run_tick_section (simulation_run_id, tick, section_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS vehicle_control_command_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  simulation_run_id VARCHAR(64) NOT NULL,
  tick BIGINT NOT NULL,
  train_id VARCHAR(64) NOT NULL,
  command_id VARCHAR(128),
  trace_id VARCHAR(64),
  operation_mode VARCHAR(32) NOT NULL,
  decision_source VARCHAR(64) NOT NULL,
  traction_command DOUBLE NOT NULL DEFAULT 0,
  brake_command DOUBLE NOT NULL DEFAULT 0,
  emergency_brake BOOLEAN NOT NULL DEFAULT FALSE,
  reason_code VARCHAR(128) NOT NULL,
  decided_at TIMESTAMP NOT NULL,
  UNIQUE KEY uk_vehicle_control_run_tick_train (simulation_run_id, tick, train_id),
  INDEX idx_vehicle_control_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS train_stop_result (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  result_id VARCHAR(192) NOT NULL UNIQUE,
  simulation_run_id VARCHAR(64) NOT NULL,
  train_id VARCHAR(64) NOT NULL,
  station_id VARCHAR(64),
  platform_id VARCHAR(64),
  target_source VARCHAR(32) NOT NULL,
  target_position_meters DOUBLE NOT NULL,
  actual_position_meters DOUBLE NOT NULL,
  signed_error_meters DOUBLE NOT NULL,
  absolute_error_meters DOUBLE NOT NULL,
  overrun BOOLEAN NOT NULL DEFAULT FALSE,
  success BOOLEAN NOT NULL DEFAULT FALSE,
  reason_code VARCHAR(128) NOT NULL,
  maximum_deceleration_mps2 DOUBLE NOT NULL DEFAULT 0,
  maximum_jerk_mps3 DOUBLE NOT NULL DEFAULT 0,
  brake_transition_count INT NOT NULL DEFAULT 0,
  emergency_brake BOOLEAN NOT NULL DEFAULT FALSE,
  control_mode VARCHAR(32),
  parameter_version VARCHAR(64) NOT NULL,
  stable_at_tick BIGINT NOT NULL,
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_train_stop_run_train (simulation_run_id, train_id, stable_at_tick)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS power_vehicle_fault_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  simulation_run_id VARCHAR(64) NOT NULL,
  tick BIGINT NOT NULL,
  fault_id VARCHAR(64) NOT NULL,
  fault_code VARCHAR(64) NOT NULL,
  source_domain VARCHAR(32) NOT NULL,
  source_ref VARCHAR(128) NOT NULL,
  affected_train_ids_json JSON,
  severity VARCHAR(32) NOT NULL,
  state VARCHAR(32) NOT NULL,
  trace_id VARCHAR(64),
  raised_at TIMESTAMP NOT NULL,
  cleared_at TIMESTAMP NULL,
  UNIQUE KEY uk_fault_run_id (simulation_run_id, fault_id),
  INDEX idx_fault_run_tick (simulation_run_id, tick),
  INDEX idx_fault_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS power_fault_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  fault_id VARCHAR(64) NOT NULL,
  section_id VARCHAR(64) NOT NULL,
  fault_type VARCHAR(64) NOT NULL,
  fault_state VARCHAR(32) NOT NULL,
  level TINYINT NOT NULL DEFAULT 2,
  affected_train_ids_json JSON,
  detail_text VARCHAR(512),
  started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  cleared_at TIMESTAMP NULL,
  INDEX idx_power_fault_section_time (section_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS power_operation_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  section_id VARCHAR(64) NOT NULL,
  operation_type VARCHAR(64) NOT NULL,
  before_state VARCHAR(64),
  after_state VARCHAR(64),
  operator_name VARCHAR(64) NOT NULL DEFAULT 'simulation',
  detail_json JSON,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_power_operation_section_time (section_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS power_maintenance_lock_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  section_id VARCHAR(64) NOT NULL,
  lockout_state VARCHAR(32) NOT NULL,
  grounding_state VARCHAR(32) NOT NULL DEFAULT 'UNGROUNDED',
  approval_state VARCHAR(32) NOT NULL DEFAULT 'SIMULATED',
  operator_name VARCHAR(64) NOT NULL DEFAULT 'simulation',
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_power_lock_section_time (section_id, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS train_fault_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  train_id VARCHAR(64) NOT NULL,
  fault_code VARCHAR(64) NOT NULL,
  fault_level TINYINT NOT NULL,
  self_check_status VARCHAR(32) NOT NULL,
  available_operation_mode VARCHAR(32) NOT NULL,
  detail_text VARCHAR(512),
  raised_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  closed_at TIMESTAMP NULL,
  INDEX idx_train_fault_train_time (train_id, raised_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS running_plan_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id VARCHAR(64) NOT NULL,
  line_id VARCHAR(64) NOT NULL,
  period_type VARCHAR(32) NOT NULL,
  start_time TIME NOT NULL,
  end_time TIME NOT NULL,
  departure_interval_sec INT NOT NULL,
  default_dwell_time_sec INT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_plan_line_window (plan_id, line_id, start_time, end_time),
  INDEX idx_running_plan_line_enabled (line_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS train_station_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  simulation_run_id VARCHAR(64) NOT NULL,
  train_id VARCHAR(64) NOT NULL,
  line_id VARCHAR(64) NOT NULL,
  station_id VARCHAR(64) NOT NULL,
  planned_arrival TIMESTAMP NULL,
  actual_arrival TIMESTAMP NULL,
  planned_departure TIMESTAMP NULL,
  actual_departure TIMESTAMP NULL,
  arrival_delay_sec INT DEFAULT 0,
  departure_delay_sec INT DEFAULT 0,
  simulated_at TIMESTAMP NOT NULL,
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_train_station_run (simulation_run_id, train_id, station_id),
  INDEX idx_train_station_time (train_id, simulated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS disturbance_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  simulation_run_id VARCHAR(64) NOT NULL,
  train_id VARCHAR(64) NOT NULL,
  station_id VARCHAR(64),
  disturbance_type VARCHAR(64) NOT NULL,
  deviation_value DOUBLE NOT NULL,
  deviation_unit VARCHAR(16) NOT NULL DEFAULT 'SECONDS',
  status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
  command_id VARCHAR(64),
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at TIMESTAMP NULL,
  INDEX idx_disturbance_train_time (train_id, recorded_at),
  INDEX idx_disturbance_status (status, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO line_config (line_id, line_name, length_meters, default_speed_limit_mps)
VALUES ('demo-line-1', '上京地铁示范线', 5000, 22.2)
ON DUPLICATE KEY UPDATE
  line_name = VALUES(line_name),
  length_meters = VALUES(length_meters),
  default_speed_limit_mps = VALUES(default_speed_limit_mps);

INSERT INTO station_config (station_id, line_id, station_name, position_meters, platform_capacity)
VALUES
  ('S01', 'demo-line-1', '上京南站', 0, 1200),
  ('S02', 'demo-line-1', '科创园站', 1250, 1000),
  ('S03', 'demo-line-1', '中央公园站', 2500, 1000),
  ('S04', 'demo-line-1', '北城站', 3750, 1000),
  ('S05', 'demo-line-1', '上京北站', 5000, 1200)
ON DUPLICATE KEY UPDATE
  station_name = VALUES(station_name),
  position_meters = VALUES(position_meters),
  platform_capacity = VALUES(platform_capacity);

INSERT INTO track_segment_config (segment_id, line_id, from_node, to_node, start_meters, end_meters, speed_limit_mps)
VALUES
  ('T01', 'demo-line-1', 'S01', 'S02', 0, 1250, 20),
  ('T02', 'demo-line-1', 'S02', 'S03', 1250, 2500, 22.2),
  ('T03', 'demo-line-1', 'S03', 'S04', 2500, 3750, 22.2),
  ('T04', 'demo-line-1', 'S04', 'S05', 3750, 5000, 20)
ON DUPLICATE KEY UPDATE
  start_meters = VALUES(start_meters),
  end_meters = VALUES(end_meters),
  speed_limit_mps = VALUES(speed_limit_mps);

INSERT INTO power_section_config (
  section_id,
  line_id,
  section_name,
  substation_id,
  feeder_id,
  start_meters,
  end_meters,
  nominal_voltage,
  breaker_status,
  isolator_status,
  supply_mode,
  maintenance_state,
  lockout_state
)
VALUES
  ('P01', 'demo-line-1', '南段供电分区', 'SS01', 'F01', 0, 2500, 1500, 'CLOSED', 'CLOSED', 'DOUBLE_END', 'NONE', 'UNLOCKED'),
  ('P02', 'demo-line-1', '北段供电分区', 'SS02', 'F02', 2500, 5000, 1500, 'CLOSED', 'CLOSED', 'DOUBLE_END', 'NONE', 'UNLOCKED')
ON DUPLICATE KEY UPDATE
  section_name = VALUES(section_name),
  substation_id = VALUES(substation_id),
  feeder_id = VALUES(feeder_id),
  start_meters = VALUES(start_meters),
  end_meters = VALUES(end_meters),
  nominal_voltage = VALUES(nominal_voltage),
  breaker_status = VALUES(breaker_status),
  isolator_status = VALUES(isolator_status),
  supply_mode = VALUES(supply_mode),
  maintenance_state = VALUES(maintenance_state),
  lockout_state = VALUES(lockout_state);

INSERT INTO system_config (config_key, config_value, description)
VALUES
  ('simulation.tickMillis', '200', '仿真固定步长，单位毫秒'),
  ('simulation.pushIntervalMillis', '1000', 'WebSocket 快照推送间隔，单位毫秒'),
  ('simulation.safetyGapMeters', '120', '移动授权安全间隔，单位米'),
  ('simulation.fmuStepMillis', '100', 'FMU 车辆物理模型步长，单位毫秒'),
  ('simulation.trackStepMillis', '100', '轨道占用更新步长，单位毫秒'),
  ('simulation.signalStepMillis', '100', '信号 MA/限速计算步长，单位毫秒'),
  ('simulation.powerStepMillis', '100', '接触轨供电计算步长，单位毫秒'),
  ('simulation.dispatchStepMillis', '1000', '调度策略判断步长，单位毫秒'),
  ('simulation.persistenceStepMillis', '5000', 'MySQL 快照持久化步长，单位毫秒'),
  ('simulation.fmuServiceEnabled', 'false', '是否启用外部 Python FMU 服务')
ON DUPLICATE KEY UPDATE
  config_value = VALUES(config_value),
  description = VALUES(description);

INSERT INTO running_plan_config
  (plan_id, line_id, period_type, start_time, end_time, departure_interval_sec, default_dwell_time_sec)
VALUES
  ('RP-demo-001', 'demo-line-1', 'PEAK', '07:00:00', '09:00:00', 180, 30),
  ('RP-demo-001', 'demo-line-1', 'PEAK', '17:00:00', '19:00:00', 180, 30),
  ('RP-demo-001', 'demo-line-1', 'FLAT', '09:00:00', '17:00:00', 300, 25),
  ('RP-demo-001', 'demo-line-1', 'OFF_PEAK', '19:00:00', '07:00:00', 420, 20)
ON DUPLICATE KEY UPDATE
  departure_interval_sec = VALUES(departure_interval_sec),
  default_dwell_time_sec = VALUES(default_dwell_time_sec);
