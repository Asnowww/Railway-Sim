-- Vehicle-signal interface database change log.
-- Purpose: persist the vehicle-side fault speed limit reported through the
-- train content packet and exposed to the signal command projection.

ALTER TABLE train_physics_snapshot
  ADD COLUMN vehicle_fault_speed_limit_mps DOUBLE NOT NULL DEFAULT 0 AFTER speed_limit_mps;

-- Canonical create-table definitions are maintained in database/schema.sql.
