package com.researchagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.researchagent.service.ResearchOrchestratorService;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the bulk-delete endpoint's rollback behavior.
 * The rollback logic is tested at the service layer (ResearchOrchestratorServiceTest).
 * This class verifies that the controller correctly delegates to the service
 * and that empty/invalid requests return 400.
 */
@WebMvcTest(ResearchController.class)
class ResearchControllerBulkDeleteHistoryRollbackTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ResearchOrchestratorService orchestratorService;

    // ---------- Empty/invalid request handling ----------

    @Test
    void bulkDeleteHistorySessions_shouldReturn400WhenSessionIdIsNull() throws Exception {
        String body = "null";

        mockMvc.perform(post("/api/research/history/bulk-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());

        verify(orchestratorService, never()).deleteSessionsInBulk(anyList());
    }

    @Test
    void bulkDeleteHistorySessions_shouldReturn400WhenSessionIdsAreEmpty() throws Exception {
        String body = "[]";

        mockMvc.perform(post("/api/research/history/bulk-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());

        verify(orchestratorService, never()).deleteSessionsInBulk(anyList());
    }

    // ---------- Rollback via service exception ----------

    @Test
    void bulkDeleteHistorySessions_shouldReturn409WhenServiceRejectsProcessingSession() throws Exception {
        UUID sessionId = UUID.randomUUID();
        doThrow(new IllegalStateException("Cannot delete session: " + sessionId + " — is still processing"))
                .when(orchestratorService).deleteSessionsInBulk(anyList());

        String body = "[\"" + sessionId.toString() + "\"]";

        // The controller catches IllegalStateException and returns 409 with no body
        mockMvc.perform(post("/api/research/history/bulk-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isConflict());

        verify(orchestratorService).deleteSessionsInBulk(anyList());
    }
}
