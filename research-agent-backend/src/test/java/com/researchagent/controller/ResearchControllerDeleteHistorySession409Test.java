package com.researchagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResearchController.class)
class ResearchControllerDeleteHistorySession409Test {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.researchagent.service.ResearchOrchestratorService orchestratorService;

    @Test
    void deleteHistorySession_shouldReturn409WhenProcessing() throws Exception {
        // RED: This test should fail because the endpoint doesn't handle PROCESSING sessions yet
        
        UUID processingId = UUID.randomUUID();
        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(createMockSession(processingId, com.researchagent.model.enums.ResearchStatus.PROCESSING));

        mockMvc.perform(delete("/api/research/history/{sessionId}", processingId)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isConflict());

        // Verify cancel is NOT called (only the existing cancel endpoint handles PROCESSING sessions)
        verify(orchestratorService, never()).cancelResearch(any(UUID.class));
    }

    private com.researchagent.model.entity.ResearchSession createMockSession(UUID id, com.researchagent.model.enums.ResearchStatus status) {
        return null; // Placeholder — will need proper setup after implementation
    }
}
