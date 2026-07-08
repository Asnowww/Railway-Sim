-- Vehicle load and overload protection database change log.
-- Purpose: persist the vehicle-layer dynamic load and derating diagnostics
-- derived from the external protocol train content packet.

-- train_physics_snapshot:
--   load_mass_kg stores the protocol/runtime dynamic train load in kg.
--   overload_status records NORMAL, NEAR_CAPACITY, OVERLOAD, or CRITICAL_OVERLOAD.
--   available_traction_count and available_brake_count store the usable traction
--   and braking unit counts used by TCMS/ATO derating logic.
--   vehicle_protection_reason records the vehicle-side protection reason exposed
--   to signal. Signal/ATS decides whether it becomes a departure inhibit.

ALTER TABLE train_physics_snapshot
  ADD COLUMN load_mass_kg DOUBLE NOT NULL DEFAULT 0 AFTER data_quality,
  ADD COLUMN overload_status VARCHAR(32) NOT NULL DEFAULT 'NORMAL' AFTER load_mass_kg,
  ADD COLUMN available_traction_count INT NOT NULL DEFAULT 6 AFTER overload_status,
  ADD COLUMN available_brake_count INT NOT NULL DEFAULT 6 AFTER available_traction_count,
  ADD COLUMN vehicle_protection_reason VARCHAR(64) NOT NULL DEFAULT 'NONE' AFTER available_brake_count;

-- Canonical create-table definitions are maintained in database/schema.sql.
