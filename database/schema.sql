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

