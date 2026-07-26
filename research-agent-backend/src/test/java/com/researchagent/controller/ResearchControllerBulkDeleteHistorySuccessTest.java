package com.researchagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResearchController.class)
class ResearchControllerBulkDeleteHistorySuccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.researchagent.service.ResearchOrchestratorService orchestratorService;

    @Test
    void bulkDeleteHistorySessions_shouldReturn204WhenSuccessfullyDeleted() throws Exception {
        // RED: This test should fail because the endpoint doesn't exist yet
        
        UUID sessionId1 = UUID.randomUUID();
        UUID sessionId2 = UUID.randomUUID();
        
        com.researchagent.model.entity.ResearchSession mockSession1 = new com.researchagent.model.entity.ResearchSession();
        mockSession1.setId(sessionId1);

        com.researchagent.model.entity.ResearchSession mockSession2 = new com.researchagent.model.entity.ResearchSession();
        mockSession2.setId(sessionId2);

        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(mockSession1, mockSession2);
        doNothing().when(orchestratorService).deleteSessionsInBulk(anyList());

        String body = "[\"" + sessionId1.toString() + "\", \"" + sessionId2.toString() + "\"]";

        mockMvc.perform(post("/api/research/history/bulk-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNoContent());
    }
}
