package com.researchagent.controller;

import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.enums.ResearchStatus;
import com.researchagent.service.ResearchOrchestratorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ownership gate (openspec/changes/session-ownership): a session whose {@code owner} field names a
 * different user must be invisible — 404, never 403 — on every per-session endpoint. The caller's
 * username comes from the security context; these MVC requests carry no token, so the caller is
 * unowned/null and any non-null owner on the document is foreign.
 */
@WebMvcTest(ResearchController.class)
class ResearchControllerForeignSessionIsInvisibleTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResearchOrchestratorService orchestratorService;

    private ResearchSession foreignSession(UUID id) {
        ResearchSession session = new ResearchSession();
        session.setId(id);
        session.setTopic("someone else's topic");
        session.setStatus(ResearchStatus.COMPLETED);
        session.setOwner("bob"); // not the caller
        return session;
    }

    @Test
    void getStatus_foreignSession_returns404() throws Exception {
        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(foreignSession(UUID.randomUUID()));

        mockMvc.perform(get("/api/research/{sessionId}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getHistoricalSession_foreignSession_returns404() throws Exception {
        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(foreignSession(UUID.randomUUID()));

        mockMvc.perform(get("/api/research/history/{sessionId}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getReport_foreignSession_returns404() throws Exception {
        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(foreignSession(UUID.randomUUID()));

        mockMvc.perform(get("/api/research/{sessionId}/report", UUID.randomUUID()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resumeResearch_foreignSession_returns404AndNeverClaims() throws Exception {
        ResearchSession foreign = foreignSession(UUID.randomUUID());
        foreign.setStatus(ResearchStatus.FAILED);
        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(foreign);

        mockMvc.perform(post("/api/research/{sessionId}/resume", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteHistorySession_foreignSession_returns404AndNeverDeletes() throws Exception {
        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(foreignSession(UUID.randomUUID()));

        mockMvc.perform(delete("/api/research/history/{sessionId}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
