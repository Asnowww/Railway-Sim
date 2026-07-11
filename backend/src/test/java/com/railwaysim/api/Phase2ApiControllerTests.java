package com.railwaysim.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.railwaysim.train.VehicleSpecificationCatalog;
import com.railwaysim.vehicle.external.ExternalTrainDirection;
import com.railwaysim.vehicle.drivercab.DriverCabDirectionHandleState;
import com.railwaysim.vehicle.drivercab.DriverCabDoorModeSwitch;
import com.railwaysim.vehicle.drivercab.DriverCabMasterHandleState;
import com.railwaysim.vehicle.drivercab.DriverCabPlcCodec;
import com.railwaysim.vehicle.drivercab.DriverCabPlcInputPacket;
import com.railwaysim.vehicle.protocol.SignalTrainContentCodec;
import com.railwaysim.vehicle.protocol.TrainOperationalTelemetry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:phase2-api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password="
})
@AutoConfigureMockMvc
class Phase2ApiControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VehicleSpecificationCatalog vehicleSpecificationCatalog;

    @Autowired
    private ObjectMapper objectMapper;

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
    }

    @Test
    void requestRouteCommandRunsThroughDispatchQueueAndInterlockingFeedback() throws Exception {
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
                      "routeId": "R_D1"
                    }
                    """))
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
            .andExpect(jsonPath("$[0].controlSessionState").value("IN_SERVICE"))
            .andExpect(jsonPath("$[0].signalNetworkStatus").value("ATTACHED"));
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
            .andExpect(jsonPath("$[0].headMileage").value(987.65))
            .andExpect(jsonPath("$[0].speedMetersPerSecond").value(12.34))
            .andExpect(jsonPath("$[0].overloadStatus").value("CRITICAL_OVERLOAD"))
            .andExpect(jsonPath("$[0].availableTractionCount").value(4))
            .andExpect(jsonPath("$[0].vehicleFaultSpeedLimitMetersPerSecond").value(2.0))
            .andExpect(jsonPath("$[0].faultCode").value("ATP_BRAKE"));

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
            .andExpect(jsonPath("$[1].trainId").value("TR-002"))
            .andExpect(jsonPath("$[1].headMileage").value(456.78))
            .andExpect(jsonPath("$[1].loadMassKg").value(76000));

        mockMvc.perform(get("/api/signal/vehicles/commands"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].trainId").value("TR-001"))
            .andExpect(jsonPath("$[0].tractionCutoff").value(true))
            .andExpect(jsonPath("$[0].serviceBrakeCommand").value(true))
            .andExpect(jsonPath("$[0].emergencyBrakeCommand").value(true))
            .andExpect(jsonPath("$[0].reason").value("NO_MOVEMENT_AUTHORITY"));
    }

    @Test
    void driverCabPlcPacketUpdatesSingleTrainCabState() throws Exception {
        mockMvc.perform(post("/api/simulation/reset"))
            .andExpect(status().isOk());
        DriverCabPlcCodec codec = new DriverCabPlcCodec();
        byte[] inputPayload = codec.encodeInput(new DriverCabPlcInputPacket(
            true,
            true,
            false,
            true,
            true,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            DriverCabDoorModeSwitch.MANUAL,
            false,
            true,
            false,
            true,
            true,
            DriverCabDirectionHandleState.FORWARD,
            DriverCabMasterHandleState.FAST_BRAKE,
            0,
            90
        ));

        mockMvc.perform(post("/api/vehicle/driver-cabs/TR-001/plc-input")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(inputPayload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trainId").value("TR-001"))
            .andExpect(jsonPath("$.faultCode").value("DRIVER_CAB_EMERGENCY_BRAKE"))
            .andExpect(jsonPath("$.driverConsoleState.doorModeSwitchState").value("MANUAL"))
            .andExpect(jsonPath("$.driverConsoleState.atoStartFlag").value(true))
            .andExpect(jsonPath("$.driverConsoleState.modeDowngradeConfirmFlag").value(true))
            .andExpect(jsonPath("$.driverConsoleState.masterHandleState").value("FAST_BRAKE"));

        mockMvc.perform(get("/api/vehicle/driver-cabs/TR-001/state"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.doorModeSwitchState").value("MANUAL"))
            .andExpect(jsonPath("$.brakeNotchPercent").value(90));

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
