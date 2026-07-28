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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResearchController.class)
class ResearchControllerBulkDeleteHistorySuccessTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ResearchOrchestratorService orchestratorService;

    @Test
    void bulkDeleteHistorySessions_shouldReturn204WhenAllDeletedSuccessfully() throws Exception {
        // Given: two completed sessions and successful bulk delete
        UUID sessionId1 = UUID.randomUUID();
        UUID sessionId2 = UUID.randomUUID();

        ResearchSession mockSession1 = new ResearchSession();
        mockSession1.setId(sessionId1);
        ResearchSession mockSession2 = new ResearchSession();
        mockSession2.setId(sessionId2);

        when(orchestratorService.getResearch(sessionId1)).thenReturn(mockSession1);
        when(orchestratorService.getResearch(sessionId2)).thenReturn(mockSession2);
        doReturn(List.of()).when(orchestratorService).deleteSessionsInBulk(anyList());

        // JSON body with quoted UUID strings (proper JSON format)
        String body = "[\"" + sessionId1.toString() + "\"," + "\"" + sessionId2.toString() + "\"]";

        mockMvc.perform(post("/api/research/history/bulk-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNoContent());

        verify(orchestratorService).deleteSessionsInBulk(anyList());
    }

    @Test
    void bulkDeleteHistorySessions_shouldReturn400WhenBodyIsEmpty() throws Exception {
        String body = "[]";

        mockMvc.perform(post("/api/research/history/bulk-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());

        verify(orchestratorService, never()).deleteSessionsInBulk(anyList());
    }

    @Test
    void bulkDeleteHistorySessions_shouldReturn400WhenBodyIsNull() throws Exception {
        String body = "null";

        mockMvc.perform(post("/api/research/history/bulk-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }
}
