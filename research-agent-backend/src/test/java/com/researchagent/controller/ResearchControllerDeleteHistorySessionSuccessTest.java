package com.researchagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResearchController.class)
class ResearchControllerDeleteHistorySessionSuccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.researchagent.service.ResearchOrchestratorService orchestratorService;

    @Test
    void deleteHistorySession_shouldReturn204WhenSuccessfullyDeleted() throws Exception {
        // RED: This test should fail because the endpoint doesn't handle deletion yet
        
        UUID completedId = UUID.randomUUID();
        com.researchagent.model.entity.ResearchSession mockSession = new com.researchagent.model.entity.ResearchSession();
        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(mockSession);

        mockMvc.perform(delete("/api/research/history/{sessionId}", completedId))
            .andExpect(status().isNoContent());
