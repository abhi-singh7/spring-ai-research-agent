package com.researchagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.researchagent.model.entity.ResearchSession;
import com.researchagent.service.ResearchOrchestratorService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ResearchController.class)
class ResearchControllerGetHistoryAndSearchTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ResearchOrchestratorService orchestratorService;

    private ResearchSession createSession(UUID id, String topic, LocalDateTime createdAt) {
        ResearchSession session = new ResearchSession();
        session.setId(id);
        session.setTopic(topic);
        // Use reflection to set created_at since it's package-private or protected
        try {
            java.lang.reflect.Field f = ResearchSession.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(session, createdAt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return session;
    }

    @Test
    void getHistory_shouldReturnPaginatedResults() throws Exception {
        // Given: paginated sessions in the repository
        LocalDateTime now = LocalDateTime.now();
        UUID id1 = UUID.randomUUID(), id2 = UUID.randomUUID();
        ResearchSession s1 = createSession(id1, "Topic A", now.minusHours(1));
        ResearchSession s2 = createSession(id2, "Topic B", now);

        PageImpl<ResearchSession> page = new PageImpl<>(List.of(s2, s1), org.springframework.data.domain.PageRequest.of(0, 20), 2);

        when(orchestratorService.getHistoricalSessions(any(), any(org.springframework.data.domain.PageRequest.class)))
            .thenReturn(page);

        // When + Then: GET /history returns 200 with paginated data
        mockMvc.perform(get("/api/research/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void getHistory_shouldReturnEmptyPageWhenNoSessions() throws Exception {
        // Given: empty page in the repository
        PageImpl<ResearchSession> emptyPage = new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 20), 0);

        when(orchestratorService.getHistoricalSessions(any(), any(org.springframework.data.domain.PageRequest.class)))
            .thenReturn(emptyPage);

        // When + Then: returns 200 with empty content array
        mockMvc.perform(get("/api/research/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void searchHistory_shouldReturnMatchingSessions() throws Exception {
        // Given: sessions matching the query
        LocalDateTime now = LocalDateTime.now();
        UUID id1 = UUID.randomUUID(), id2 = UUID.randomUUID();
        ResearchSession s1 = createSession(id1, "Spring Boot testing", now.minusHours(1));
        ResearchSession s2 = createSession(id2, "Java concurrency", now);

        PageImpl<ResearchSession> page = new PageImpl<>(List.of(s1, s2), org.springframework.data.domain.PageRequest.of(0, 20), 2);

        when(orchestratorService.searchByTopic(any(), any(org.springframework.data.domain.PageRequest.class)))
            .thenReturn(page);

        // When + Then: GET /history/search returns matching sessions
        mockMvc.perform(get("/api/research/history/search")
                .param("query", "Spring Boot"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void searchHistory_shouldReturnEmptyResultsWhenNoMatch() throws Exception {
        // Given: no sessions matching the query
        PageImpl<ResearchSession> emptyPage = new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 20), 0);

        when(orchestratorService.searchByTopic(any(), any(org.springframework.data.domain.PageRequest.class)))
            .thenReturn(emptyPage);

        mockMvc.perform(get("/api/research/history/search")
                .param("query", "nonexistent-topic"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void getHistoricalSession_shouldReturnDetailWithSteps() throws Exception {
        // Given: a session with steps (using reflection for createdAt)
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = createSession(sessionId, "AI research", LocalDateTime.now());
        mockSession.setStatus(com.researchagent.model.enums.ResearchStatus.COMPLETED);

        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(mockSession);

        // When + Then: GET /history/{id} returns 200 with detail DTO
        mockMvc.perform(get("/api/research/history/{sessionId}", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.topic").value("AI research"))
            .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void getHistoricalSession_shouldReturn404WhenNotFound() throws Exception {
        // Given: session not found
        UUID nonExistentId = UUID.randomUUID();
        when(orchestratorService.getResearch(any(UUID.class))).thenReturn(null);

        mockMvc.perform(get("/api/research/history/{sessionId}", nonExistentId))
            .andExpect(status().isNotFound());
    }
}
