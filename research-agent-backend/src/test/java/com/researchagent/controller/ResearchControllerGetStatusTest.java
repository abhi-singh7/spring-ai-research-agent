package com.researchagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.enums.ResearchStatus;
import com.researchagent.service.ResearchOrchestratorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ResearchController.class)
class ResearchControllerGetStatusTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ResearchOrchestratorService orchestratorService;

    @Test
    void getStatus_shouldReturn200WithSessionDetails() throws Exception {
        // Given: a PROCESSING session with topic and status
        UUID sessionId = UUID.randomUUID();
        String topic = "Quantum computing";

        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        mockSession.setTopic(topic);
        mockSession.setStatus(ResearchStatus.PROCESSING);

        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(mockSession);

        // When + Then: GET returns 200 with correct fields
        mockMvc.perform(get("/api/research/{sessionId}", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(sessionId.toString()))
            .andExpect(jsonPath("$.topic").value(topic))
            .andExpect(jsonPath("$.status").value("PROCESSING"))
            .andExpect(jsonPath("$.finalReport").doesNotExist());
    }

    @Test
    void getStatus_shouldReturn200WithCompletedSessionAndReport() throws Exception {
        // Given: a COMPLETED session with final report
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        mockSession.setTopic("AI safety");
        mockSession.setStatus(ResearchStatus.COMPLETED);
        mockSession.setFinalReport("# AI Safety Report\n\nContent...");

        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(mockSession);

        // When + Then: returns 200 with report included
        mockMvc.perform(get("/api/research/{sessionId}", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.finalReport").value("# AI Safety Report\n\nContent..."))
            .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void getStatus_shouldReturn404WhenSessionNotFound() throws Exception {
        // Given: session does not exist
        UUID nonExistentId = UUID.randomUUID();
        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(null);

        // When + Then: returns 404 Not Found
        mockMvc.perform(get("/api/research/{sessionId}", nonExistentId))
            .andExpect(status().isNotFound());
    }

    @Test
    void getStatus_shouldReturnEmptyStepsWhenNoSteps() throws Exception {
        // Given: session with no steps (steps is null)
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        mockSession.setTopic("test");
        mockSession.setStatus(ResearchStatus.PROCESSING);

        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(mockSession);

        // When + Then: steps should be empty list, not null
        mockMvc.perform(get("/api/research/{sessionId}", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.steps").isArray());
    }
}
