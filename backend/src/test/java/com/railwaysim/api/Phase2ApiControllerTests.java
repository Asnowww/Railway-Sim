package com.railwaysim.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.train.VehicleSpecificationCatalog;
import com.railwaysim.train.TrainManager;
import com.railwaysim.vehicle.external.ExternalTrainDirection;
import com.railwaysim.vehicle.protocol.SignalTrainContentCodec;
import com.railwaysim.vehicle.protocol.TrainOperationalTelemetry;
import com.railwaysim.vehicle.telemetry.VehicleTelemetryGatewayService;
import com.railwaysim.vehicle.telemetry.VehicleTelemetryResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:phase2-api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "railway.simulation.line-data-path=../config/line-demo.yaml",
    "railway.simulation.vehicle-runtime.base-url=http://127.0.0.1:1",
    "railway.simulation.vehicle-runtime.timeout-millis=50"
})
@AutoConfigureMockMvc
class Phase2ApiControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private VehicleSpecificationCatalog vehicleSpecificationCatalog;

    @Autowired
    private TrainManager trainManager;

    @Autowired
    private StaticInfrastructureCatalog infrastructureCatalog;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private VehicleTelemetryGatewayService telemetryGatewayService;

    @BeforeEach
    void createAuditAndRunTables() {
        when(telemetryGatewayService.forward(any(), any()))
            .thenReturn(new VehicleTelemetryResponse(true, List.of()));
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS operation_log (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              operator_name VARCHAR(64) NOT NULL,
              operation_type VARCHAR(64) NOT NULL,
              target_ref VARCHAR(128) NOT NULL,
              detail_json JSON,
              run_id VARCHAR(64), tick BIGINT, trace_id VARCHAR(64),
              before_state VARCHAR(1024), after_state VARCHAR(1024), reason VARCHAR(512),
              status VARCHAR(32) NOT NULL, retry_count INT NOT NULL,
              error_text VARCHAR(1024), created_at TIMESTAMP NOT NULL
            )
            """);
        trainManager.reset();
        trainManager.registerRuntimeStartedTrain("TR-001", 1, 100.0, ExternalTrainDirection.DOWN);
        trainManager.registerRuntimeStartedTrain("TR-002", 2, 900.0, ExternalTrainDirection.DOWN);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS simulation_run (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              run_id VARCHAR(64) NOT NULL UNIQUE,
              status VARCHAR(32) NOT NULL,
              created_at TIMESTAMP NOT NULL,
              started_at TIMESTAMP NULL,
              ended_at TIMESTAMP NULL,
              last_tick BIGINT NOT NULL DEFAULT 0,
              end_reason VARCHAR(255)
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS alarm_record (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, alarm_id VARCHAR(255) NOT NULL UNIQUE,
              simulation_run_id VARCHAR(64) NOT NULL, alarm_code VARCHAR(192) NOT NULL,
              source_module VARCHAR(64) NOT NULL, location_ref VARCHAR(128) NOT NULL,
              level TINYINT NOT NULL, title VARCHAR(128) NOT NULL, detail_text VARCHAR(512) NOT NULL,
              state VARCHAR(32) NOT NULL, confirmed BOOLEAN NOT NULL,
              raised_at TIMESTAMP NOT NULL, last_seen_at TIMESTAMP NOT NULL,
              acknowledged_at TIMESTAMP NULL, acknowledged_by VARCHAR(64), cleared_at TIMESTAMP NULL,
              affected_train_ids_json JSON, affected_section_ids_json JSON,
              safety_action VARCHAR(128), clear_condition VARCHAR(255), recovery_condition VARCHAR(255)
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS service_health_record (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, service_id VARCHAR(64) NOT NULL UNIQUE,
              state VARCHAR(32) NOT NULL, data_quality VARCHAR(32) NOT NULL,
              source_timestamp TIMESTAMP NULL, observed_at TIMESTAMP NOT NULL,
              simulation_run_id VARCHAR(64), last_accepted_tick BIGINT NOT NULL,
              topology_hash VARCHAR(128), config_hash VARCHAR(128), model_version VARCHAR(128),
              parameter_version VARCHAR(128), reason_text VARCHAR(512), recovery_gate_json JSON
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS service_health_baseline (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, service_id VARCHAR(64) NOT NULL UNIQUE,
              simulation_run_id VARCHAR(64), last_accepted_tick BIGINT NOT NULL,
              topology_hash VARCHAR(128) NOT NULL, config_hash VARCHAR(128) NOT NULL,
              model_version VARCHAR(128) NOT NULL, parameter_version VARCHAR(128) NOT NULL,
              source_timestamp TIMESTAMP NULL
            )
            """);
    }

    @Test
    void exposesTrainPowerEnergyAndMaintenanceReadApis() throws Exception {
        mockMvc.perform(get("/api/trains"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].id").value("TR-001"))
            .andExpect(jsonPath("$[0].dynamicsState").exists());

        mockMvc.perform(get("/api/power/sections"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(5)))
            .andExpect(jsonPath("$[0].substationId").value("SS01"))
            .andExpect(jsonPath("$[0].feederId").value("F01"))
            .andExpect(jsonPath("$[4].id").value("P05"));

        mockMvc.perform(get("/api/energy/trains"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].netEnergyKwh").exists());

        mockMvc.perform(get("/api/energy/power-sections"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections", hasSize(5)))
            .andExpect(jsonPath("$.sections[4].sectionId").value("P05"));

        mockMvc.perform(get("/api/vehicle/maintenance-states"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)));

        mockMvc.perform(get("/api/operation-logs/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").exists());
    }

    @Test
    void exposesUnifiedServiceHealthWithoutAllowingRecoveryCheckWhileUp() throws Exception {
        mockMvc.perform(get("/api/simulation/snapshot"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/service-health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].state").value("FALLBACK"))
            .andExpect(jsonPath("$[1].state").value("UP"));

        mockMvc.perform(post("/api/service-health/vehicle-runtime-9300/recovery/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedRunId":"local-run","expectedTick":0}
                    """))
            .andExpect(status().isConflict());
    }

    @Test
    @Disabled("requires a 9300 integration fixture now that central LOCAL mode is removed")
    void requestRouteCommandRunsThroughDispatchQueueAndInterlockingFeedback() throws Exception {
        String validRouteId = infrastructureCatalog.lineData().routes().get(0).id();
        mockMvc.perform(post("/api/simulation/reset"))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/trains/lifecycle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "action": "CLEAR",
                      "trains": [],
                      "reason": "isolate route command test",
                      "operator": "api-test",
                      "confirmToken": "SIMULATION_CONFIRM",
                      "traceId": "trace-route-command"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)));

        mockMvc.perform(post("/api/simulation/tick"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/simulation/tick"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trains", hasSize(0)));

        mockMvc.perform(post("/api/trains/lifecycle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "action": "ADD",
                      "trains": [{
                        "trainNo": 1,
                        "linkId": 1,
                        "offsetMeters": 100,
                        "direction": "DOWN"
                      }],
                      "reason": "isolate route command test",
                      "operator": "api-test",
                      "confirmToken": "SIMULATION_CONFIRM",
                      "traceId": "trace-route-command"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)));

        MvcResult submittedResult = mockMvc.perform(post("/api/dispatch/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "trainId": "TR-001",
                      "commandType": "REQUEST_ROUTE",
                      "routeId": "%s"
                    }
                    """.formatted(validRouteId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.payload.decisionId").isNotEmpty())
            .andExpect(jsonPath("$.payload.reservationId").isNotEmpty())
            .andReturn();

        JsonNode submitted = objectMapper.readTree(submittedResult.getResponse().getContentAsString());
        String commandId = submitted.path("id").asText();
        String decisionId = submitted.path("payload").path("decisionId").asText();
        String reservationId = submitted.path("payload").path("reservationId").asText();

        mockMvc.perform(post("/api/simulation/tick"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.routeStates[0].status").value("OCCUPIED"))
            .andExpect(jsonPath("$.dispatch.routeDecisions[0].decisionId").value(decisionId))
            .andExpect(jsonPath("$.dispatch.routeDecisions[0].routeCommandId").value(commandId))
            .andExpect(jsonPath("$.dispatch.routeReservations[0].reservationId").value(reservationId))
            .andExpect(jsonPath("$.dispatch.routeReservations[0].decisionId").value(decisionId))
            .andExpect(jsonPath("$.dispatch.routeReservations[0].commandId").value(commandId));

        mockMvc.perform(get("/api/dispatch/commands"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].commandType").value("REQUEST_ROUTE"))
            .andExpect(jsonPath("$[0].status").value("EFFECT_CONFIRMED"))
            .andExpect(jsonPath("$[0].payload.decisionId").value(decisionId))
            .andExpect(jsonPath("$[0].payload.reservationId").value(reservationId))
            .andExpect(jsonPath("$[0].payload.lastFeedbackSource").value("SIGNAL_INTERLOCKING"))
            .andExpect(jsonPath("$[0].payload.lastFeedbackDetails.accepted").value(true));

        mockMvc.perform(post("/api/dispatch/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "trainId": "TR-001",
                      "commandType": "REQUEST_ROUTE",
                      "routeId": "R_NOT_FOUND"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(post("/api/simulation/tick"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/dispatch/commands"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[1].status").value("SKIPPED"))
            .andExpect(jsonPath("$[1].payload.lastFeedbackDetails.accepted").value(false));
    }

    @Test
    void faultMutationApisRequireConfirmationAndUpdateState() throws Exception {
        mockMvc.perform(post("/api/power/sections/P01/faults")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "faultType": "UNDERVOLTAGE",
                      "reason": "test",
                      "operator": "api-test",
                      "traceId": "trace-test"
                    }
                    """))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/power/sections/P01/faults")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "faultType": "UNDERVOLTAGE",
                      "reason": "test",
                      "operator": "api-test",
                      "confirmToken": "SIMULATION_CONFIRM",
                      "traceId": "trace-test"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UNDERVOLTAGE"));

        mockMvc.perform(get("/api/power/sections/P01/events"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].eventType").value("POWER_FAULT"));

        mockMvc.perform(post("/api/trains/TR-001/faults")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "faultType": "DOOR_NOT_LOCKED",
                      "reason": "test",
                      "operator": "api-test",
                      "confirmToken": "SIMULATION_CONFIRM",
                      "traceId": "trace-test"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.faultCode").value("DOOR_NOT_LOCKED"));

        mockMvc.perform(get("/api/trains/TR-001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.doorState").value("OPEN"))
            .andExpect(jsonPath("$.selfCheckStatus").value("FAIL"))
            .andExpect(jsonPath("$.faultLevel").value(3));
    }

    @Test
    void trainLifecycleApiAcceptsExternalControlSessionCommand() throws Exception {
        mockMvc.perform(post("/api/trains/lifecycle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "action": "ADD",
                      "trains": [
                        {
                          "trainNo": 1,
                          "linkId": 1,
                          "offsetMeters": 100,
                          "direction": "DOWN"
                        }
                      ],
                      "reason": "api-test",
                      "operator": "signal-test",
                      "confirmToken": "SIMULATION_CONFIRM",
                      "traceId": "trace-lifecycle"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].id").value("TR-001"))
            .andExpect(jsonPath("$[0].controlSessionState").value("CONNECTING"))
            .andExpect(jsonPath("$[0].signalNetworkStatus").value("NOT_ATTACHED"));
    }

    @Test
    void vehicleRuntimeRegistrationCreatesCentralMirror() throws Exception {
        mockMvc.perform(post("/api/simulation/reset"))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/trains/runtime-registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(runtimeRegistrationRequest(
                    "TR-901",
                    vehicleSpecificationCatalog.specification().parameterSetId()
                )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("TR-901"))
            .andExpect(jsonPath("$.positionMeters").value(450.0))
            .andExpect(jsonPath("$.lengthMeters").value(118.0))
            .andExpect(jsonPath("$.controlSessionState").value("CONNECTING"))
            .andExpect(jsonPath("$.linkId").value(9))
            .andExpect(jsonPath("$.direction").value("DOWN"));

        // 服务间注册接口只建立中央镜像；清理后避免影响同一 Spring 上下文内的其它接口测试。
        mockMvc.perform(post("/api/simulation/reset"))
            .andExpect(status().isOk());
    }

    @Test
    void vehicleRuntimeRegistrationRejectsMismatchedParameterSet() throws Exception {
        mockMvc.perform(post("/api/simulation/reset"))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/trains/runtime-registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(runtimeRegistrationRequest(
                    "TR-902",
                    "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                )))
            .andExpect(status().isConflict());
    }

    @Test
    void signalVehicleInterfaceAcceptsOperationalTelemetryAndProjectsCommands() throws Exception {
        mockMvc.perform(post("/api/simulation/reset"))
            .andExpect(status().isOk());
        trainManager.registerRuntimeStartedTrain("TR-001", 1, 100.0, ExternalTrainDirection.DOWN);
        trainManager.registerRuntimeStartedTrain("TR-002", 2, 900.0, ExternalTrainDirection.DOWN);

        mockMvc.perform(get("/api/signal/vehicles/statuses"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].trainId").value("TR-001"))
            .andExpect(jsonPath("$[0].faultCode").value("OK"));

        mockMvc.perform(post("/api/signal/vehicles/telemetry")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    [
                      {
                        "trainNo": 1,
                        "speedMetersPerSecond": 12.34,
                        "cumulativeDistanceMeters": 987.65,
                        "direction": "DOWN",
                        "loadMassKg": 105000,
                        "faultSpeedLimitMetersPerSecond": 2.0,
                        "emergencyBrakeApplied": true,
                        "availableTractionCount": 4,
                        "availableBrakeCount": 5
                      }
                    ]
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(true));

        byte[] contentPacket = new SignalTrainContentCodec().encode(List.of(new TrainOperationalTelemetry(
            2,
            8.0,
            456.78,
            ExternalTrainDirection.UP,
            76_000,
            0,
            false,
            6,
            6
        )));
        mockMvc.perform(post("/api/signal/vehicles/telemetry/content-packet")
                .param("trainCount", "1")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(contentPacket))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(true));

        mockMvc.perform(get("/api/signal/vehicles/commands"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].trainId").value("TR-001"))
            .andExpect(jsonPath("$[0].tractionCutoff").value(true))
            .andExpect(jsonPath("$[0].serviceBrakeCommand").value(true))
            .andExpect(jsonPath("$[0].emergencyBrakeCommand").value(false))
            .andExpect(jsonPath("$[0].reason").value("CONTROL_SESSION_CONNECTING"));
    }

    @Test
    void centralPlcGatewayRejectsRawBinaryAndKeepsDisplayReadEndpoint() throws Exception {
        mockMvc.perform(post("/api/vehicle/driver-cabs/TR-001/plc-input")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(new byte[46]))
            .andExpect(status().isUnsupportedMediaType());

        mockMvc.perform(get("/api/vehicle/driver-cabs/TR-001/plc-output"))
            .andExpect(status().isOk());
    }

    private static String runtimeRegistrationRequest(String trainId, String parameterSetId) {
        return """
            {
              "trainId": "%s",
              "linkId": 9,
              "offsetMeters": 450.0,
              "direction": "DOWN",
              "reason": "runtime-test",
              "traceId": "trace-runtime",
              "trainType": "B_TYPE_6_CAR",
              "parameterSetId": "%s",
              "lengthMeters": 118.0,
              "emptyMassKg": 225000,
              "maxLoadMassKg": 76000
            }
            """.formatted(trainId, parameterSetId);
    }
}
