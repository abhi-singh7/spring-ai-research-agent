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

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResearchController.class)
class ResearchControllerDeleteHistorySessionSuccessTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ResearchOrchestratorService orchestratorService;

    @Test
    void deleteHistorySession_shouldReturn204WhenSuccessfullyDeleted() throws Exception {
        // Given: a completed session exists
        UUID completedId = UUID.randomUUID();
        ResearchSession mockSession = createSession(completedId, ResearchStatus.COMPLETED);

        when(orchestratorService.getResearch(any())).thenReturn(mockSession);

        // When + Then: DELETE returns 204 No Content
        mockMvc.perform(delete("/api/research/history/{sessionId}", completedId))
            .andExpect(status().isNoContent());

        // Verify delete was called on the service
        verify(orchestratorService).deleteSession(completedId);
    }

    @Test
    void deleteHistorySession_shouldReturn204ForCancelledSessions() throws Exception {
        // Given: a cancelled session exists
        UUID cancelledId = UUID.randomUUID();
        ResearchSession mockSession = createSession(cancelledId, ResearchStatus.CANCELLED);

        when(orchestratorService.getResearch(any())).thenReturn(mockSession);

        mockMvc.perform(delete("/api/research/history/{sessionId}", cancelledId))
            .andExpect(status().isNoContent());
    }

    @Test
    void deleteHistorySession_shouldReturn204ForFailedSessions() throws Exception {
        // Given: a failed session exists
        UUID failedId = UUID.randomUUID();
        ResearchSession mockSession = createSession(failedId, ResearchStatus.FAILED);

        when(orchestratorService.getResearch(any())).thenReturn(mockSession);

        mockMvc.perform(delete("/api/research/history/{sessionId}", failedId))
            .andExpect(status().isNoContent());
    }

    private ResearchSession createSession(UUID id, ResearchStatus status) {
        ResearchSession session = new ResearchSession();
        session.setId(id);
        session.setStatus(status);
        return session;
    }
}
