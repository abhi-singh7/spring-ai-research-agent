package com.researchagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.enums.ResearchStatus;
import com.researchagent.service.ResearchOrchestratorService;

import java.util.UUID;

import static org.hamcrest.Matchers.empty;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResearchController.class)
class ResearchControllerCancelResearchTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ResearchOrchestratorService orchestratorService;

    @Test
    void cancelResearch_shouldReturn204WhenSessionIsProcessing() throws Exception {
        // Given: a PROCESSING session exists
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        mockSession.setStatus(ResearchStatus.PROCESSING);

        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(mockSession);

        // When + Then: DELETE returns 204 No Content
        mockMvc.perform(delete("/api/research/{sessionId}", sessionId))
            .andExpect(status().isNoContent());

        verify(orchestratorService).cancelResearch(sessionId);
    }

    @Test
    void cancelResearch_shouldReturn400WhenSessionNotFound() throws Exception {
        // Given: session does not exist
        UUID nonExistentId = UUID.randomUUID();
        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(null);

        // When + Then: returns 400 Bad Request (session is null)
        mockMvc.perform(delete("/api/research/{sessionId}", nonExistentId))
            .andExpect(status().isBadRequest());

        verify(orchestratorService, never()).cancelResearch(any(UUID.class));
    }

    @Test
    void cancelResearch_shouldReturn400WhenSessionIsNotProcessing() throws Exception {
        // Given: session is COMPLETED — cannot be cancelled (already finished)
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        mockSession.setStatus(ResearchStatus.COMPLETED);

        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(mockSession);

        // When + Then: returns 400 Bad Request
        mockMvc.perform(delete("/api/research/{sessionId}", sessionId))
            .andExpect(status().isBadRequest());

        verify(orchestratorService, never()).cancelResearch(sessionId);
    }

    @Test
    void cancelResearch_shouldReturn400WhenSessionIsCancelled() throws Exception {
        // Given: session is already CANCELLED — cannot be cancelled again
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        mockSession.setStatus(ResearchStatus.CANCELLED);

        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(mockSession);

        mockMvc.perform(delete("/api/research/{sessionId}", sessionId))
            .andExpect(status().isBadRequest());

        verify(orchestratorService, never()).cancelResearch(sessionId);
    }
}
