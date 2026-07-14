-- MySQL dump 10.13  Distrib 8.4.10, for Linux (x86_64)
--
-- Host: host.docker.internal    Database: railway_sim
-- ------------------------------------------------------
-- Server version	8.0.42-0ubuntu0.20.04.1

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `alarm_record`
--

DROP TABLE IF EXISTS `alarm_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `alarm_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_module` varchar(64) NOT NULL,
  `location_ref` varchar(128) NOT NULL,
  `level` tinyint NOT NULL,
  `title` varchar(128) NOT NULL,
  `detail_text` varchar(512) NOT NULL,
  `confirmed` tinyint(1) NOT NULL DEFAULT '0',
  `raised_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_alarm_record_level_time` (`level`,`raised_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `alarm_record`
--

LOCK TABLES `alarm_record` WRITE;
/*!40000 ALTER TABLE `alarm_record` DISABLE KEYS */;
/*!40000 ALTER TABLE `alarm_record` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `balise_config`
--

DROP TABLE IF EXISTS `balise_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `balise_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `balise_id` varchar(64) NOT NULL,
  `line_id` varchar(64) NOT NULL,
  `hex_id` varchar(64) DEFAULT NULL,
  `balise_name` varchar(128) DEFAULT NULL,
  `segment_id` varchar(64) DEFAULT NULL,
  `position_meters` double NOT NULL,
  `interoperability_id` varchar(128) DEFAULT NULL,
  `attribute_code` varchar(32) DEFAULT NULL,
  `linked_signal_id` varchar(64) DEFAULT NULL,
  `direction_code` varchar(32) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `balise_id` (`balise_id`),
  KEY `idx_balise_line_position` (`line_id`,`position_meters`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `balise_config`
--

LOCK TABLES `balise_config` WRITE;
/*!40000 ALTER TABLE `balise_config` DISABLE KEYS */;
/*!40000 ALTER TABLE `balise_config` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `dispatch_command_record`
--

DROP TABLE IF EXISTS `dispatch_command_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `dispatch_command_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `command_id` varchar(64) NOT NULL,
  `train_id` varchar(64) DEFAULT NULL,
  `command_type` varchar(64) NOT NULL,
  `payload_json` json NOT NULL,
  `status` varchar(32) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_dispatch_command_status_time` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `dispatch_command_record`
--

LOCK TABLES `dispatch_command_record` WRITE;
/*!40000 ALTER TABLE `dispatch_command_record` DISABLE KEYS */;
/*!40000 ALTER TABLE `dispatch_command_record` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `disturbance_record`
--

DROP TABLE IF EXISTS `disturbance_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `disturbance_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `simulation_run_id` varchar(64) NOT NULL,
  `train_id` varchar(64) NOT NULL,
  `station_id` varchar(64) DEFAULT NULL,
  `disturbance_type` varchar(64) NOT NULL,
  `deviation_value` double NOT NULL,
  `deviation_unit` varchar(16) NOT NULL DEFAULT 'SECONDS',
  `status` varchar(32) NOT NULL DEFAULT 'OPEN',
  `command_id` varchar(64) DEFAULT NULL,
  `recorded_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `resolved_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_disturbance_train_time` (`train_id`,`recorded_at`),
  KEY `idx_disturbance_status` (`status`,`recorded_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `disturbance_record`
--

LOCK TABLES `disturbance_record` WRITE;
/*!40000 ALTER TABLE `disturbance_record` DISABLE KEYS */;
/*!40000 ALTER TABLE `disturbance_record` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `fmu_call_log`
--

DROP TABLE IF EXISTS `fmu_call_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `fmu_call_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tick` bigint NOT NULL,
  `service_url` varchar(255) NOT NULL,
  `request_count` int NOT NULL,
  `status` varchar(32) NOT NULL,
  `elapsed_millis` bigint NOT NULL DEFAULT '0',
  `detail_text` varchar(512) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_fmu_call_log_tick_status` (`tick`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `fmu_call_log`
--

LOCK TABLES `fmu_call_log` WRITE;
/*!40000 ALTER TABLE `fmu_call_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `fmu_call_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `fmu_fault_log`
--

DROP TABLE IF EXISTS `fmu_fault_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `fmu_fault_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tick` bigint NOT NULL DEFAULT '0',
  `train_id` varchar(64) DEFAULT NULL,
  `fault_code` varchar(64) NOT NULL,
  `detail_text` varchar(512) NOT NULL,
  `fallback_activated` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_fmu_fault_tick_train` (`tick`,`train_id`),
  KEY `idx_fmu_fault_log_train_time` (`train_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `fmu_fault_log`
--

LOCK TABLES `fmu_fault_log` WRITE;
/*!40000 ALTER TABLE `fmu_fault_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `fmu_fault_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `gradient_zone_config`
--

DROP TABLE IF EXISTS `gradient_zone_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `gradient_zone_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `zone_id` varchar(64) NOT NULL,
  `line_id` varchar(64) NOT NULL,
  `start_meters` double NOT NULL,
  `end_meters` double NOT NULL,
  `gradient` double NOT NULL,
  `raw_permille_value` int NOT NULL DEFAULT '0',
  `direction_code` varchar(32) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `zone_id` (`zone_id`),
  KEY `idx_gradient_line_range` (`line_id`,`start_meters`,`end_meters`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `gradient_zone_config`
--

LOCK TABLES `gradient_zone_config` WRITE;
/*!40000 ALTER TABLE `gradient_zone_config` DISABLE KEYS */;
/*!40000 ALTER TABLE `gradient_zone_config` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `line_config`
--

DROP TABLE IF EXISTS `line_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `line_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `line_id` varchar(64) NOT NULL,
  `line_name` varchar(128) NOT NULL,
  `length_meters` double NOT NULL,
  `default_speed_limit_mps` double NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `line_id` (`line_id`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `line_config`
--

LOCK TABLES `line_config` WRITE;
/*!40000 ALTER TABLE `line_config` DISABLE KEYS */;
INSERT INTO `line_config` VALUES (1,'demo-line-1','上京地铁示范线',5000,22.2,1,'2026-07-07 03:24:07','2026-07-07 03:24:07');
/*!40000 ALTER TABLE `line_config` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `operation_log`
--

DROP TABLE IF EXISTS `operation_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `operation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `operator_name` varchar(64) NOT NULL,
  `operation_type` varchar(64) NOT NULL,
  `target_ref` varchar(128) NOT NULL,
  `detail_json` json DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_operation_log_type_time` (`operation_type`,`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `operation_log`
--

LOCK TABLES `operation_log` WRITE;
/*!40000 ALTER TABLE `operation_log` DISABLE KEYS */;
INSERT INTO `operation_log` VALUES (1,'signal-demo','TRAIN_EXTERNAL_CONTROL_LIFECYCLE','trains:lifecycle','{\"reason\": \"demo attach via cloud db ssh tunnel\", \"traceId\": \"demo-cloud-ssh-trains\", \"operator\": \"signal-demo\", \"createdAt\": \"2026-07-08T06:42:43.694327Z\", \"targetRef\": \"trains:lifecycle\", \"afterState\": \"ADD,count=3\", \"beforeState\": \"count=2\", \"operationType\": \"TRAIN_EXTERNAL_CONTROL_LIFECYCLE\"}','2026-07-08 06:42:44'),(2,'signal-demo','TRAIN_EXTERNAL_CONTROL_LIFECYCLE','trains:lifecycle','{\"reason\": \"html demo attach\", \"traceId\": \"html-demo-1783493183741\", \"operator\": \"signal-demo\", \"createdAt\": \"2026-07-08T06:46:23.746947Z\", \"targetRef\": \"trains:lifecycle\", \"afterState\": \"ADD,count=3\", \"beforeState\": \"count=5\", \"operationType\": \"TRAIN_EXTERNAL_CONTROL_LIFECYCLE\"}','2026-07-08 06:46:24'),(3,'signal-demo','TRAIN_EXTERNAL_CONTROL_LIFECYCLE','trains:lifecycle','{\"reason\": \"html demo attach\", \"traceId\": \"html-demo-1783493194666\", \"operator\": \"signal-demo\", \"createdAt\": \"2026-07-08T06:46:34.671034Z\", \"targetRef\": \"trains:lifecycle\", \"afterState\": \"ADD,count=3\", \"beforeState\": \"count=5\", \"operationType\": \"TRAIN_EXTERNAL_CONTROL_LIFECYCLE\"}','2026-07-08 06:46:35'),(4,'signal-demo','TRAIN_EXTERNAL_CONTROL_LIFECYCLE','trains:lifecycle','{\"reason\": \"html demo attach\", \"traceId\": \"html-demo-1783493209886\", \"operator\": \"signal-demo\", \"createdAt\": \"2026-07-08T06:46:49.890590Z\", \"targetRef\": \"trains:lifecycle\", \"afterState\": \"ADD,count=3\", \"beforeState\": \"count=2\", \"operationType\": \"TRAIN_EXTERNAL_CONTROL_LIFECYCLE\"}','2026-07-08 06:46:50'),(5,'vehicle-runtime','TRAIN_RUNTIME_REGISTER','train:TR-DB1','{\"reason\": \"full startup db test\", \"traceId\": \"full-db-test\", \"operator\": \"vehicle-runtime\", \"createdAt\": \"2026-07-09T06:05:45.468844Z\", \"targetRef\": \"train:TR-DB1\", \"afterState\": \"CONNECTING\", \"beforeState\": \"runtime-launch\", \"operationType\": \"TRAIN_RUNTIME_REGISTER\"}','2026-07-09 06:05:45');
/*!40000 ALTER TABLE `operation_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `passenger_flow_record`
--

DROP TABLE IF EXISTS `passenger_flow_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `passenger_flow_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `station_id` varchar(64) NOT NULL,
  `inbound_count` int NOT NULL DEFAULT '0',
  `outbound_count` int NOT NULL DEFAULT '0',
  `waiting_count` int NOT NULL DEFAULT '0',
  `load_rate` double NOT NULL DEFAULT '0',
  `recorded_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_passenger_flow_station_time` (`station_id`,`recorded_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `passenger_flow_record`
--

LOCK TABLES `passenger_flow_record` WRITE;
/*!40000 ALTER TABLE `passenger_flow_record` DISABLE KEYS */;
/*!40000 ALTER TABLE `passenger_flow_record` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `platform_config`
--

DROP TABLE IF EXISTS `platform_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `platform_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `platform_id` varchar(64) NOT NULL,
  `line_id` varchar(64) NOT NULL,
  `center_meters` double NOT NULL,
  `anchor_segment_id` varchar(64) DEFAULT NULL,
  `direction_code` varchar(32) DEFAULT NULL,
  `raw_center_mark` varchar(64) DEFAULT NULL,
  `interoperability_id` varchar(128) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `platform_id` (`platform_id`),
  KEY `idx_platform_line_position` (`line_id`,`center_meters`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `platform_config`
--

LOCK TABLES `platform_config` WRITE;
/*!40000 ALTER TABLE `platform_config` DISABLE KEYS */;
/*!40000 ALTER TABLE `platform_config` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `point_config`
--

DROP TABLE IF EXISTS `point_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `point_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `point_id` varchar(64) NOT NULL,
  `line_id` varchar(64) NOT NULL,
  `point_name` varchar(128) DEFAULT NULL,
  `track_name` varchar(128) DEFAULT NULL,
  `kilometer_mark_meters` double NOT NULL,
  `direction_code` varchar(32) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `point_id` (`point_id`),
  KEY `idx_point_config_line_position` (`line_id`,`kilometer_mark_meters`)
) ENGINE=InnoDB AUTO_INCREMENT=262 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `point_config`
--

LOCK TABLES `point_config` WRITE;
/*!40000 ALTER TABLE `point_config` DISABLE KEYS */;
/*!40000 ALTER TABLE `point_config` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `power_fault_record`
--

DROP TABLE IF EXISTS `power_fault_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `power_fault_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `fault_id` varchar(64) NOT NULL,
  `section_id` varchar(64) NOT NULL,
  `fault_type` varchar(64) NOT NULL,
  `fault_state` varchar(32) NOT NULL,
  `level` tinyint NOT NULL DEFAULT '2',
  `affected_train_ids_json` json DEFAULT NULL,
  `detail_text` varchar(512) DEFAULT NULL,
  `started_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `cleared_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_power_fault_section_time` (`section_id`,`started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `power_fault_record`
--

LOCK TABLES `power_fault_record` WRITE;
/*!40000 ALTER TABLE `power_fault_record` DISABLE KEYS */;
/*!40000 ALTER TABLE `power_fault_record` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `power_maintenance_lock_record`
--

DROP TABLE IF EXISTS `power_maintenance_lock_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `power_maintenance_lock_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `section_id` varchar(64) NOT NULL,
  `lockout_state` varchar(32) NOT NULL,
  `grounding_state` varchar(32) NOT NULL DEFAULT 'UNGROUNDED',
  `approval_state` varchar(32) NOT NULL DEFAULT 'SIMULATED',
  `operator_name` varchar(64) NOT NULL DEFAULT 'simulation',
  `recorded_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_power_lock_section_time` (`section_id`,`recorded_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `power_maintenance_lock_record`
--

LOCK TABLES `power_maintenance_lock_record` WRITE;
/*!40000 ALTER TABLE `power_maintenance_lock_record` DISABLE KEYS */;
/*!40000 ALTER TABLE `power_maintenance_lock_record` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `power_operation_log`
--

DROP TABLE IF EXISTS `power_operation_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `power_operation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `section_id` varchar(64) NOT NULL,
  `operation_type` varchar(64) NOT NULL,
  `before_state` varchar(64) DEFAULT NULL,
  `after_state` varchar(64) DEFAULT NULL,
  `operator_name` varchar(64) NOT NULL DEFAULT 'simulation',
  `detail_json` json DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_power_operation_section_time` (`section_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `power_operation_log`
--

LOCK TABLES `power_operation_log` WRITE;
/*!40000 ALTER TABLE `power_operation_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `power_operation_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `power_section_config`
--

DROP TABLE IF EXISTS `power_section_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `power_section_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `section_id` varchar(64) NOT NULL,
  `line_id` varchar(64) NOT NULL,
  `section_name` varchar(128) NOT NULL,
  `start_meters` double NOT NULL,
  `end_meters` double NOT NULL,
  `nominal_voltage` double NOT NULL DEFAULT '1500',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `section_id` (`section_id`),
  KEY `idx_power_section_line_range` (`line_id`,`start_meters`,`end_meters`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `power_section_config`
--

LOCK TABLES `power_section_config` WRITE;
/*!40000 ALTER TABLE `power_section_config` DISABLE KEYS */;
INSERT INTO `power_section_config` VALUES (1,'P01','demo-line-1','南段供电分区',0,2500,1500,'2026-07-07 03:24:07'),(2,'P02','demo-line-1','北段供电分区',2500,5000,1500,'2026-07-07 03:24:07');
/*!40000 ALTER TABLE `power_section_config` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `power_section_record`
--

DROP TABLE IF EXISTS `power_section_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `power_section_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `section_id` varchar(64) NOT NULL,
  `tick` bigint NOT NULL,
  `voltage` double NOT NULL,
  `current_value` double NOT NULL,
  `status` varchar(32) NOT NULL,
  `load_w` double NOT NULL DEFAULT '0',
  `available_power_w` double NOT NULL DEFAULT '0',
  `regen_power_w` double NOT NULL DEFAULT '0',
  `absorbed_regen_power_w` double NOT NULL DEFAULT '0',
  `unabsorbed_regen_power_w` double NOT NULL DEFAULT '0',
  `breaker_status` varchar(32) NOT NULL DEFAULT 'CLOSED',
  `protection_state` varchar(32) NOT NULL DEFAULT 'NORMAL',
  `maintenance_state` varchar(32) NOT NULL DEFAULT 'NONE',
  `lockout_state` varchar(32) NOT NULL DEFAULT 'UNLOCKED',
  `affected_train_ids_json` json DEFAULT NULL,
  `data_quality` varchar(32) NOT NULL DEFAULT 'GOOD',
  `recorded_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_power_section_record_section_tick` (`section_id`,`tick`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `power_section_record`
--

LOCK TABLES `power_section_record` WRITE;
/*!40000 ALTER TABLE `power_section_record` DISABLE KEYS */;
INSERT INTO `power_section_record` VALUES (1,'P01',50,1395.9717825365394,1300.3527182932573,'ENERGIZED',1817861.4159047308,2791943.565073079,0,0,0,'CLOSED','NORMAL','NONE','UNLOCKED','[\"TR-001\", \"TR-002\"]','GOOD','2026-07-09 02:57:09'),(2,'P02',50,1500,0,'ENERGIZED',0,3000000,0,0,0,'CLOSED','NORMAL','NONE','UNLOCKED','[]','GOOD','2026-07-09 02:57:09');
/*!40000 ALTER TABLE `power_section_record` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `power_state_record`
--

DROP TABLE IF EXISTS `power_state_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `power_state_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `section_id` varchar(64) NOT NULL,
  `voltage` double NOT NULL,
  `current_value` double NOT NULL,
  `status` varchar(32) NOT NULL,
  `recorded_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_power_state_section_time` (`section_id`,`recorded_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `power_state_record`
--

LOCK TABLES `power_state_record` WRITE;
/*!40000 ALTER TABLE `power_state_record` DISABLE KEYS */;
/*!40000 ALTER TABLE `power_state_record` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `power_vehicle_fault_record`
--

DROP TABLE IF EXISTS `power_vehicle_fault_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `power_vehicle_fault_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `simulation_run_id` varchar(64) NOT NULL,
  `tick` bigint NOT NULL,
  `fault_id` varchar(64) NOT NULL,
  `fault_code` varchar(64) NOT NULL,
  `source_domain` varchar(32) NOT NULL,
  `source_ref` varchar(128) NOT NULL,
  `affected_train_ids_json` json DEFAULT NULL,
  `severity` varchar(32) NOT NULL,
  `state` varchar(32) NOT NULL,
  `trace_id` varchar(64) DEFAULT NULL,
  `raised_at` timestamp NOT NULL,
  `cleared_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_fault_run_id` (`simulation_run_id`,`fault_id`),
  KEY `idx_fault_run_tick` (`simulation_run_id`,`tick`),
  KEY `idx_fault_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `power_vehicle_fault_record`
--

LOCK TABLES `power_vehicle_fault_record` WRITE;
/*!40000 ALTER TABLE `power_vehicle_fault_record` DISABLE KEYS */;
/*!40000 ALTER TABLE `power_vehicle_fault_record` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `protocol_packet_log`
--

DROP TABLE IF EXISTS `protocol_packet_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `protocol_packet_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `protocol_family` varchar(64) NOT NULL,
  `adapter_id` varchar(128) NOT NULL,
  `packet_direction` varchar(32) NOT NULL,
  `byte_length` int NOT NULL DEFAULT '0',
  `packet_summary` varchar(1000) DEFAULT NULL,
  `process_status` varchar(32) NOT NULL,
  `error_message` varchar(1000) DEFAULT NULL,
  `recorded_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_protocol_packet_log_adapter_time` (`adapter_id`,`recorded_at`),
  KEY `idx_protocol_packet_log_family_time` (`protocol_family`,`recorded_at`),
  KEY `idx_protocol_packet_log_status_time` (`process_status`,`recorded_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `protocol_packet_log`
--

LOCK TABLES `protocol_packet_log` WRITE;
/*!40000 ALTER TABLE `protocol_packet_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `protocol_packet_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `route_config`
--

DROP TABLE IF EXISTS `route_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `route_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `route_id` varchar(64) NOT NULL,
  `line_id` varchar(64) NOT NULL,
  `route_name` varchar(128) DEFAULT NULL,
  `type_code` varchar(32) DEFAULT NULL,
  `start_signal_id` varchar(64) DEFAULT NULL,
  `end_signal_id` varchar(64) DEFAULT NULL,
  `axle_section_ids_json` json DEFAULT NULL,
  `protection_section_ids_json` json DEFAULT NULL,
  `point_approach_section_ids_json` json DEFAULT NULL,
  `cbtc_approach_section_ids_json` json DEFAULT NULL,
  `point_trigger_section_ids_json` json DEFAULT NULL,
  `cbtc_trigger_section_ids_json` json DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `route_id` (`route_id`),
  KEY `idx_route_line_signals` (`line_id`,`start_signal_id`,`end_signal_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `route_config`
--

LOCK TABLES `route_config` WRITE;
/*!40000 ALTER TABLE `route_config` DISABLE KEYS */;
/*!40000 ALTER TABLE `route_config` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `running_plan_config`
--

DROP TABLE IF EXISTS `running_plan_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `running_plan_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `plan_id` varchar(64) NOT NULL,
  `line_id` varchar(64) NOT NULL,
  `period_type` varchar(32) NOT NULL,
  `start_time` time NOT NULL,
  `end_time` time NOT NULL,
  `departure_interval_sec` int NOT NULL,
  `default_dwell_time_sec` int NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plan_line_period` (`plan_id`,`line_id`,`period_type`),
  KEY `idx_running_plan_line_enabled` (`line_id`,`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `running_plan_config`
--

LOCK TABLES `running_plan_config` WRITE;
/*!40000 ALTER TABLE `running_plan_config` DISABLE KEYS */;
/*!40000 ALTER TABLE `running_plan_config` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `service_health_baseline`
--

DROP TABLE IF EXISTS `service_health_baseline`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `service_health_baseline` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `service_id` varchar(64) NOT NULL,
  `simulation_run_id` varchar(64) DEFAULT NULL,
  `last_accepted_tick` bigint NOT NULL DEFAULT '-1',
  `topology_hash` varchar(128) NOT NULL,
  `config_hash` varchar(128) NOT NULL,
  `model_version` varchar(128) NOT NULL,
  `parameter_version` varchar(128) NOT NULL,
  `source_timestamp` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `service_id` (`service_id`)
) ENGINE=InnoDB AUTO_INCREMENT=17485 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `service_health_baseline`
--

LOCK TABLES `service_health_baseline` WRITE;
/*!40000 ALTER TABLE `service_health_baseline` DISABLE KEYS */;
INSERT INTO `service_health_baseline` VALUES (1,'vehicle-runtime-9300','f1cc9d5b-e582-46ec-83b5-3eabf6ed7ac5',930,'NOT_APPLICABLE','LOCAL','LOCAL_JAVA','LOCAL','2026-07-13 02:40:03'),(2,'power-network-9200','f1cc9d5b-e582-46ec-83b5-3eabf6ed7ac5',929,'LOCAL','LOCAL','LOCAL_POWER','LOCAL','2026-07-13 02:40:02');
/*!40000 ALTER TABLE `service_health_baseline` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `service_health_record`
--

DROP TABLE IF EXISTS `service_health_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `service_health_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `service_id` varchar(64) NOT NULL,
  `state` varchar(32) NOT NULL,
  `data_quality` varchar(32) NOT NULL,
  `source_timestamp` timestamp NULL DEFAULT NULL,
  `observed_at` timestamp NOT NULL,
  `simulation_run_id` varchar(64) DEFAULT NULL,
  `last_accepted_tick` bigint NOT NULL DEFAULT '-1',
  `topology_hash` varchar(128) DEFAULT NULL,
  `config_hash` varchar(128) DEFAULT NULL,
  `model_version` varchar(128) DEFAULT NULL,
  `parameter_version` varchar(128) DEFAULT NULL,
  `reason_text` varchar(512) DEFAULT NULL,
  `recovery_gate_json` json DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `service_id` (`service_id`),
  KEY `idx_service_health_state` (`state`,`observed_at`)
) ENGINE=InnoDB AUTO_INCREMENT=45535 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `service_health_record`
--

LOCK TABLES `service_health_record` WRITE;
/*!40000 ALTER TABLE `service_health_record` DISABLE KEYS */;
INSERT INTO `service_health_record` VALUES (1,'vehicle-runtime-9300','UP','GOOD','2026-07-13 02:40:03','2026-07-13 02:40:03','f1cc9d5b-e582-46ec-83b5-3eabf6ed7ac5',930,'NOT_APPLICABLE','LOCAL','LOCAL_JAVA','LOCAL','LOCAL_RUNTIME',NULL),(2,'power-network-9200','UP','GOOD','2026-07-13 02:40:02','2026-07-13 02:40:02','f1cc9d5b-e582-46ec-83b5-3eabf6ed7ac5',929,'LOCAL','LOCAL','LOCAL_POWER','LOCAL','LOCAL',NULL);
/*!40000 ALTER TABLE `service_health_record` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `signal_config`
--

DROP TABLE IF EXISTS `signal_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `signal_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `signal_id` varchar(64) NOT NULL,
  `line_id` varchar(64) NOT NULL,
  `signal_name` varchar(128) DEFAULT NULL,
  `type_code` varchar(32) DEFAULT NULL,
  `attribute_code` varchar(32) DEFAULT NULL,
  `segment_id` varchar(64) DEFAULT NULL,
  `position_meters` double NOT NULL,
  `protection_direction_code` varchar(32) DEFAULT NULL,
  `lamp_info_code` varchar(64) DEFAULT NULL,
  `interoperability_id` varchar(128) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `signal_id` (`signal_id`),
  KEY `idx_signal_line_position` (`line_id`,`position_meters`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `signal_config`
--

LOCK TABLES `signal_config` WRITE;
/*!40000 ALTER TABLE `signal_config` DISABLE KEYS */;
