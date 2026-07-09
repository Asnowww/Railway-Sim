-- External train control session status.
-- Scope: central-system side only. These columns record whether an externally
-- started vehicle-control subsystem is attached to signal and power networks;
-- they do not model the vehicle-control subsystem's internal boot process.

ALTER TABLE train_physics_snapshot
  ADD COLUMN control_session_state VARCHAR(32) NOT NULL DEFAULT 'IN_SERVICE' AFTER tick,
  ADD COLUMN signal_network_status VARCHAR(32) NOT NULL DEFAULT 'ATTACHED' AFTER control_session_state,
  ADD COLUMN power_network_status VARCHAR(32) NOT NULL DEFAULT 'ATTACHED' AFTER signal_network_status,
  ADD COLUMN control_session_reason VARCHAR(128) NOT NULL DEFAULT 'EXTERNAL_CONTROL_IN_SERVICE' AFTER power_network_status,
  ADD COLUMN link_id INT NOT NULL DEFAULT 0 AFTER control_session_reason,
  ADD COLUMN direction VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' AFTER link_id;
