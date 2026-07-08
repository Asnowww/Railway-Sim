package com.railwaysim.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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

    @Test
    void exposesTrainPowerEnergyAndMaintenanceReadApis() throws Exception {
        mockMvc.perform(get("/api/trains"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].id").value("TR-001"))
            .andExpect(jsonPath("$[0].dynamicsState").exists());

        mockMvc.perform(get("/api/power/sections"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].substationId").value("SS01"))
            .andExpect(jsonPath("$[0].feederId").value("F01"));

        mockMvc.perform(get("/api/energy/trains"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].netEnergyKwh").exists());

        mockMvc.perform(get("/api/energy/power-sections"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sections", hasSize(2)));

        mockMvc.perform(get("/api/vehicle/maintenance-states"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)));
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
}
