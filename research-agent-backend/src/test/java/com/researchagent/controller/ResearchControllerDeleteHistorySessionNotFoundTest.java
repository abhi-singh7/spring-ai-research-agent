package com.researchagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.researchagent.service.ResearchOrchestratorService;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResearchController.class)
class ResearchControllerDeleteHistorySessionNotFoundTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ResearchOrchestratorService orchestratorService;

    @Test
    void deleteHistorySession_shouldReturn404WhenSessionNotFound() throws Exception {
        // Given: session does not exist
        UUID nonExistentId = UUID.randomUUID();
        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(null);

        // When + Then: DELETE returns 404 Not Found
        mockMvc.perform(delete("/api/research/history/{sessionId}", nonExistentId))
            .andExpect(status().isNotFound());

        // Verify no delete operation is attempted on a null session
        verify(orchestratorService, never()).deleteSession(any(UUID.class));
    }

    @Test
    void deleteHistorySession_shouldReturn400ForInvalidUuid() throws Exception {
        // Given: an invalid UUID that Spring can't parse from the path variable
        String invalidId = "not-a-valid-uuid";

        mockMvc.perform(delete("/api/research/history/{sessionId}", invalidId))
            .andExpect(status().isBadRequest());

        verify(orchestratorService, never()).deleteSession(any(UUID.class));
    }
}
