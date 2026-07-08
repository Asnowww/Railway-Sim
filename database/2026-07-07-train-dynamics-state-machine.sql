-- Train dynamics state machine database change log.
-- Purpose: persist the vehicle-layer state selected under XLS line constraints.

-- train_physics_snapshot:
--   dynamics_state records the selected vehicle dynamics phase, such as
--   ACCELERATING, STATION_BRAKE, MA_BRAKE, POWER_DERATED, or SAFETY_BRAKE.
--   dynamics_constraint_reason records the primary transition guard.
--   speed_limit_mps, ma_distance_meters, station_distance_meters, and
--   stopping_distance_meters preserve the line/signal-derived constraints
--   used by the TCMS/ATO state machine.

-- Canonical create-table definitions are maintained in database/schema.sql.
