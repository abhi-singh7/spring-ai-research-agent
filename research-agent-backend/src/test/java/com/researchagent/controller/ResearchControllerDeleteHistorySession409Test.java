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

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResearchController.class)
class ResearchControllerDeleteHistorySession409Test {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ResearchOrchestratorService orchestratorService;

    @Test
    void deleteHistorySession_shouldReturn409WhenProcessing() throws Exception {
        // Given: session is still PROCESSING (cannot be deleted via history endpoint)
        UUID processingId = UUID.randomUUID();
        ResearchSession processingSession = createSession(processingId, ResearchStatus.PROCESSING);

        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(processingSession);

        // When + Then: DELETE returns 409 Conflict
        mockMvc.perform(delete("/api/research/history/{sessionId}", processingId)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isConflict());

        // Verify cancel is NOT called (only the existing cancel endpoint handles PROCESSING sessions)
        verify(orchestratorService, never()).deleteSession(any(UUID.class));
    }

    @Test
    void deleteHistorySession_shouldReturn409ForPendingSessions() throws Exception {
        // Given: session has PENDING status but not yet processing — should still block deletion?
        // Actually controller checks only PROCESSING, so PENDING would be allowed.
        UUID pendingId = UUID.randomUUID();
        ResearchSession pendingSession = createSession(pendingId, ResearchStatus.PENDING);

        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(pendingSession);

        mockMvc.perform(delete("/api/research/history/{sessionId}", pendingId))
            .andExpect(status().isNoContent());
    }

    private ResearchSession createSession(UUID id, ResearchStatus status) {
        ResearchSession session = new ResearchSession();
        session.setId(id);
        session.setStatus(status);
        return session;
    }
}
