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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ResearchController.class)
class ResearchControllerGetReportTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ResearchOrchestratorService orchestratorService;

    @Test
    void getReport_shouldReturn200WithFinalReport() throws Exception {
        // Given: a COMPLETED session with final report
        UUID sessionId = UUID.randomUUID();
        String reportContent = "# Final Report\n\nThis is the research report.\n## Conclusion\nDone.";

        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        mockSession.setStatus(ResearchStatus.COMPLETED);
        // Use reflection to set finalReport since @Data generates it but Lombok may not be active in IDE
        try {
            java.lang.reflect.Field f = ResearchSession.class.getDeclaredField("finalReport");
            f.setAccessible(true);
            f.set(mockSession, reportContent);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(mockSession);

        // When + Then: GET returns 200 with the final report as plain text
        mockMvc.perform(get("/api/research/{sessionId}/report", sessionId))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Final Report")));
    }

    @Test
    void getReport_shouldReturnBadRequestWhenNotCompleted() throws Exception {
        // Given: a PROCESSING session (not yet complete)
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        mockSession.setStatus(ResearchStatus.PROCESSING);

        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(mockSession);

        // When + Then: returns 400 Bad Request with descriptive message
        mockMvc.perform(get("/api/research/{sessionId}/report", sessionId))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("not completed")));
    }

    @Test
    void getReport_shouldReturnBadRequestWhenSessionNotFound() throws Exception {
        // Given: session does not exist
        UUID nonExistentId = UUID.randomUUID();
        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(null);

        mockMvc.perform(get("/api/research/{sessionId}/report", nonExistentId))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("not found")));
    }

    @Test
    void getReport_shouldReturnNotFoundWhenNoReportAvailable() throws Exception {
        // Given: a COMPLETED session with no final report and no SUBTOPIC steps (empty)
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        mockSession.setStatus(ResearchStatus.COMPLETED);

        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(mockSession);

        // When + Then: returns 404 No report available
        mockMvc.perform(get("/api/research/{sessionId}/report", sessionId))
            .andExpect(status().isNotFound())
            .andExpect(content().string(containsString("No report available")));
    }
}
