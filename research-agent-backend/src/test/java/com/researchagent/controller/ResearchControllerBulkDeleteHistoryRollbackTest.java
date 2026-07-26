package com.researchagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResearchController.class)
class ResearchControllerBulkDeleteHistoryRollbackTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.researchagent.service.ResearchOrchestratorService orchestratorService;

    @Test
    void bulkDeleteHistorySessions_shouldReturn409AndRollbackWhenAnyProcessing() throws Exception {
        // RED: This test should fail because the endpoint doesn't validate PROCESSING sessions in bulk
        
        UUID processingId = UUID.randomUUID();
        UUID completedId = UUID.randomUUID();

        com.researchagent.model.entity.ResearchSession processingSession = new com.researchagent.model.entity.ResearchSession();
        processingSession.setId(processingId);

        com.researchagent.model.entity.ResearchSession completedSession = new com.researchagent.model.entity.ResearchSession();
        completedSession.setId(completedId);

        when(orchestratorService.getResearch(eq(processingId))).thenReturn(processingSession);
        when(orchestratorService.getResearch(eq(completedId))).thenReturn(completedSession);

        String body = "[" + processingId.toString() + "," + completedId.toString() + "]";

        mockMvc.perform(post("/api/research/history/bulk-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isConflict());

        // Verify no deletion was attempted (rollback behavior)
        verify(orchestratorService, never()).deleteSessionsInBulk(anyList());
    }
}
