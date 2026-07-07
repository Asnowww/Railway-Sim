-- Line data database source change log.
-- Purpose: persist database/线路数据(1).xls into MySQL and make the runtime
-- load OperationalLineData from database tables instead of the workbook file.

-- Existing base tables reused:
--   line_config, station_config, track_segment_config, switch_config.

-- New static line detail tables:
--   point_config: workbook point/kilometer mark records.
--   track_segment_topology_config: raw segment id, endpoint metadata, and
--   forward/side neighbor JSON for topology reconstruction.
--   speed_limit_zone_config: static speed limit zones from 静态限速表.
--   gradient_zone_config: gradient zones from 坡度表.
--   platform_config: platforms from 站台表.
--   station_platform_config: station to platform mapping.
--   switch_detail_config: linked switch, direction, merge segment, diverging
--   speed and interoperability metadata.
--   signal_config: signal positions and metadata.
--   balise_config: balise positions and linked signals.
--   route_config: route start/end signals and approach/trigger section lists.

-- Runtime mapping:
--   StaticInfrastructureCatalog supports railway.simulation.line-data-source.
--   The default source is database. When auto import is enabled, the startup
--   path imports railway.simulation.line-data-path into these tables before
--   JdbcLineDataLoader rebuilds OperationalLineData.

-- The canonical create-table definitions are maintained in database/schema.sql.
