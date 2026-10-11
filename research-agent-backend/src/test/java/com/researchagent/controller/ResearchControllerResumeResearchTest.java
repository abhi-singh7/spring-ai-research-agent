package com.researchagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.enums.ResearchStatus;
import com.researchagent.service.ResearchOrchestratorService;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.security.test.context.support.WithMockUser;

@WebMvcTest(ResearchController.class)
@WithMockUser
class ResearchControllerResumeResearchTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResearchOrchestratorService orchestratorService;

    private ResearchSession sessionWithStatus(UUID id, ResearchStatus status) {
        ResearchSession session = new ResearchSession();
        session.setId(id);
        session.setStatus(status);
        return session;
    }

    @Test
    void resumeResearch_shouldReturn200WhenSessionIsFailed() throws Exception {
        UUID sessionId = UUID.randomUUID();
        ResearchSession failed = sessionWithStatus(sessionId, ResearchStatus.FAILED);
        ResearchSession claimed = sessionWithStatus(sessionId, ResearchStatus.PROCESSING);

        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(failed);
        when(orchestratorService.resumeResearch(sessionId)).thenReturn(claimed);

        mockMvc.perform(post("/api/research/{sessionId}/resume", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PROCESSING"));

        verify(orchestratorService).resumeResearch(sessionId);
    }

    @Test
    void resumeResearch_shouldReturn200WhenSessionIsCancelled() throws Exception {
        UUID sessionId = UUID.randomUUID();
        ResearchSession cancelled = sessionWithStatus(sessionId, ResearchStatus.CANCELLED);
        ResearchSession claimed = sessionWithStatus(sessionId, ResearchStatus.PROCESSING);

        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(cancelled);
        when(orchestratorService.resumeResearch(sessionId)).thenReturn(claimed);

        mockMvc.perform(post("/api/research/{sessionId}/resume", sessionId))
            .andExpect(status().isOk());

        verify(orchestratorService).resumeResearch(sessionId);
    }

    @Test
    void resumeResearch_shouldReturn404WhenSessionNotFound() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(null);

        mockMvc.perform(post("/api/research/{sessionId}/resume", nonExistentId))
            .andExpect(status().isNotFound());

        verify(orchestratorService, never()).resumeResearch(any(UUID.class));
    }

    @Test
    void resumeResearch_shouldReturn409WhenSessionIsCompleted() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(sessionWithStatus(sessionId, ResearchStatus.COMPLETED));

        mockMvc.perform(post("/api/research/{sessionId}/resume", sessionId))
            .andExpect(status().isConflict());

        verify(orchestratorService, never()).resumeResearch(any(UUID.class));
    }

    @Test
    void resumeResearch_shouldReturn409WhenSessionIsProcessing() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(sessionWithStatus(sessionId, ResearchStatus.PROCESSING));

        mockMvc.perform(post("/api/research/{sessionId}/resume", sessionId))
            .andExpect(status().isConflict());

        verify(orchestratorService, never()).resumeResearch(any(UUID.class));
    }

    @Test
    void resumeResearch_shouldReturn409WhenClaimRaceIsLost() throws Exception {
        UUID sessionId = UUID.randomUUID();
        // Controller's pre-check saw FAILED, but the atomic claim lost to a concurrent resume.
        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(sessionWithStatus(sessionId, ResearchStatus.FAILED));
        when(orchestratorService.resumeResearch(sessionId))
            .thenThrow(new IllegalStateException("Session is not in a resumable state"));

        mockMvc.perform(post("/api/research/{sessionId}/resume", sessionId))
            .andExpect(status().isConflict());
    }
}
