-- Local machine-room protocol adaptation packet audit.
-- This table is a side-channel log only; realtime TCP/UDP/point-table links must
-- keep running even if this insert path is unavailable.

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
);
