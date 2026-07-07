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

CREATE TABLE IF NOT EXISTS power_section_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  section_id VARCHAR(64) NOT NULL UNIQUE,
  line_id VARCHAR(64) NOT NULL,
  section_name VARCHAR(128) NOT NULL,
  start_meters DOUBLE NOT NULL,
  end_meters DOUBLE NOT NULL,
  nominal_voltage DOUBLE NOT NULL DEFAULT 1500,
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
  segment_id VARCHAR(64) NOT NULL,
  occupancy VARCHAR(32) NOT NULL,
  train_ids_json JSON,
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_track_occupancy_segment_time (segment_id, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS signal_state_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  train_id VARCHAR(64) NOT NULL,
  authority_end_meters DOUBLE NOT NULL,
  speed_limit_mps DOUBLE NOT NULL,
  reason VARCHAR(255),
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_signal_state_train_time (train_id, recorded_at)
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
  source_module VARCHAR(64) NOT NULL,
  location_ref VARCHAR(128) NOT NULL,
  level TINYINT NOT NULL,
  title VARCHAR(128) NOT NULL,
  detail_text VARCHAR(512) NOT NULL,
  confirmed BOOLEAN NOT NULL DEFAULT FALSE,
  raised_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_alarm_record_level_time (level, raised_at)
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
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_operation_log_type_time (operation_type, created_at)
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

INSERT INTO power_section_config (section_id, line_id, section_name, start_meters, end_meters, nominal_voltage)
VALUES
  ('P01', 'demo-line-1', '南段供电分区', 0, 2500, 1500),
  ('P02', 'demo-line-1', '北段供电分区', 2500, 5000, 1500)
ON DUPLICATE KEY UPDATE
  section_name = VALUES(section_name),
  start_meters = VALUES(start_meters),
  end_meters = VALUES(end_meters),
  nominal_voltage = VALUES(nominal_voltage);

INSERT INTO system_config (config_key, config_value, description)
VALUES
  ('simulation.tickMillis', '200', '仿真固定步长，单位毫秒'),
  ('simulation.pushIntervalMillis', '1000', 'WebSocket 快照推送间隔，单位毫秒'),
  ('simulation.safetyGapMeters', '120', '移动授权安全间隔，单位米'),
  ('dispatch.averageSpeedRatio', '0.8', '计划均速相对区间限速的比例'),
  ('dispatch.dwellToleranceSec', '15', '停站超时容忍秒数'),
  ('dispatch.departureDelaySec', '30', '发车延误容忍秒数'),
  ('dispatch.headwayShrinkRatio', '0.7', '行车间隔过小比例'),
  ('dispatch.headwayExpandRatio', '1.5', '行车间隔过大比例'),
  ('dispatch.crowdingLoadRate', '0.80', '拥挤满载率阈值'),
  ('dispatch.confirmTicks', '3', '扰动确认周期数'),
  ('dispatch.cooldownSec', '60', '扰动冷却秒数'),
  ('dispatch.evaluateIntervalMs', '1000', '调度评估间隔毫秒')
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