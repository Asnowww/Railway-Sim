package com.railwaysim.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.railwaysim.signal.RouteInterlockingService;
import com.railwaysim.signal.dispatch.SignalDispatchPlanRegistry;
import com.railwaysim.dispatch.DispatchService;
import com.railwaysim.track.TrackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(SignalTrackController.class)
class SignalTrackControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TrackService trackService;

    @MockBean
    private RouteInterlockingService routeInterlockingService;

    @MockBean
    private SignalDispatchPlanRegistry signalDispatchPlanRegistry;

    @MockBean
    private DispatchService dispatchService;

    @Test
    void injectsAValidatedFault() throws Exception {
        when(trackService.segmentExists("SEG-1")).thenReturn(true);
        when(trackService.injectFault("SEG-1")).thenReturn(true);

        mockMvc.perform(post("/api/signal-track/faults")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sourceId": "SEG-1",
                      "faultType": "TRACK_CIRCUIT_UNKNOWN",
                      "operator": "signal-test",
                      "reason": "test fault",
                      "traceId": "trace-1"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(true))
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.idempotent").value(false))
            .andExpect(jsonPath("$.operation").value("INJECT"))
            .andExpect(jsonPath("$.sourceId").value("SEG-1"))
            .andExpect(jsonPath("$.faultType").value("TRACK_CIRCUIT_UNKNOWN"));
    }

    @Test
    void rejectsMissingRequiredFields() throws Exception {
        mockMvc.perform(post("/api/signal-track/faults")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"faultType\":\"TRACK_CIRCUIT_OCCUPIED\"}"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/signal-track/faults")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceId\":\"SEG-1\"}"))
            .andExpect(status().isBadRequest());

        verify(trackService, never()).injectFault(anyString());
    }

    @Test
    void rejectsAnUnknownFaultType() throws Exception {
        mockMvc.perform(post("/api/signal-track/faults")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sourceId":"SEG-1","faultType":"NOT_A_REAL_FAULT"}
                    """))
            .andExpect(status().isBadRequest());

        verify(trackService, never()).injectFault(anyString());
    }

    @Test
    void returnsNotFoundForAnUnknownSegment() throws Exception {
        when(trackService.segmentExists("SEG-404")).thenReturn(false);

        mockMvc.perform(post("/api/signal-track/faults")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sourceId":"SEG-404","faultType":"TRACK_CIRCUIT_OCCUPIED"}
                    """))
            .andExpect(status().isNotFound());

        verify(trackService, never()).injectFault(anyString());
    }

    @Test
    void returnsNotFoundWhenClearingAnUnknownSegment() throws Exception {
        when(trackService.segmentExists("SEG-404")).thenReturn(false);

        mockMvc.perform(post("/api/signal-track/faults/SEG-404/clear"))
            .andExpect(status().isNotFound());

        verify(trackService, never()).clearFault(anyString());
    }

    @Test
    void reportsRepeatedInjectionAsAnIdempotentSuccess() throws Exception {
        when(trackService.segmentExists("SEG-1")).thenReturn(true);
        when(trackService.injectFault("SEG-1")).thenReturn(true, false);

        performInjection("SEG-1")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.idempotent").value(false));
        performInjection("SEG-1")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changed").value(false))
            .andExpect(jsonPath("$.idempotent").value(true));
    }

    @Test
    void reportsRepeatedClearAsAnIdempotentSuccess() throws Exception {
        when(trackService.segmentExists("SEG-1")).thenReturn(true);
        when(trackService.clearFault("SEG-1")).thenReturn(true, false);

        mockMvc.perform(post("/api/signal-track/faults/SEG-1/clear"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.idempotent").value(false));
        mockMvc.perform(post("/api/signal-track/faults/SEG-1/clear"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changed").value(false))
            .andExpect(jsonPath("$.idempotent").value(true));
    }

    private ResultActions performInjection(String sourceId) throws Exception {
        return mockMvc.perform(post("/api/signal-track/faults")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"sourceId":"%s","faultType":"TRACK_CIRCUIT_OCCUPIED"}
                """.formatted(sourceId)));
    }
}
