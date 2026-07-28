package com.researchagent.service;

import com.researchagent.model.dto.ResearchRequest;
import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.enums.ResearchStatus;
import com.researchagent.repository.ResearchSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResearchOrchestratorServiceTest {

    @Mock private ChatClient chatClient;
    @Mock private ResearchSessionRepository sessionRepo;
    @Mock private ResearchStreamingService streamService;
    @Mock private Executor researchTaskExecutor;

    @InjectMocks
    private ResearchOrchestratorService service;

    // ---------- createAndStart ----------

    @Test
    void createAndStart_shouldPersistSessionWithProcessingStatus() {
        // Given: a valid research request
        ResearchRequest request = new ResearchRequest();
        request.setTopic("AI in healthcare");

        UUID savedId = UUID.randomUUID();
        when(sessionRepo.save(any(ResearchSession.class))).thenAnswer(invocation -> {
            ResearchSession s = invocation.getArgument(0);
            s.setId(savedId);
            return s;
        });

        // When
        ResearchSession session = service.createAndStart(request);

        // Then: session is persisted with PROCESSING status
        assertThat(session).isNotNull();
        assertThat(session.getId()).isEqualTo(savedId);
        assertThat(session.getStatus()).isEqualTo(ResearchStatus.PROCESSING);
        verify(sessionRepo).save(any(ResearchSession.class));
    }

    // ---------- getResearch ----------

    @Test
    void getResearch_shouldReturnSessionFromRepository() {
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        when(sessionRepo.findByIdWithSteps(sessionId)).thenReturn(mockSession);

        // When
        ResearchSession result = service.getResearch(sessionId);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(sessionId);
    }

    @Test
    void getResearch_shouldReturnNullWhenNotExists() {
        UUID nonExistentId = UUID.randomUUID();
        when(sessionRepo.findByIdWithSteps(nonExistentId)).thenReturn(null);

        ResearchSession result = service.getResearch(nonExistentId);
        assertThat(result).isNull();
    }

    // ---------- cancelResearch ----------

    @Test
    void cancelResearch_shouldMarkProcessingSessionAsFailed() {
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        mockSession.setStatus(ResearchStatus.PROCESSING);
        when(sessionRepo.findByIdWithSteps(sessionId)).thenReturn(mockSession);

        service.cancelResearch(sessionId);

        assertThat(mockSession.getStatus()).isEqualTo(ResearchStatus.FAILED);
        verify(streamService).sendProgress(eq(sessionId), eq("Research cancelled by user"));
    }

    @Test
    void cancelResearch_shouldDoNothingForNonProcessingSessions() {
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        mockSession.setStatus(ResearchStatus.COMPLETED);
        when(sessionRepo.findByIdWithSteps(sessionId)).thenReturn(mockSession);

        service.cancelResearch(sessionId);

        assertThat(mockSession.getStatus()).isEqualTo(ResearchStatus.COMPLETED);
        verifyNoInteractions(streamService);
    }

    @Test
    void cancelResearch_shouldDoNothingForNullSession() {
        UUID nonExistentId = UUID.randomUUID();
        when(sessionRepo.findByIdWithSteps(nonExistentId)).thenReturn(null);

        service.cancelResearch(nonExistentId);
        verifyNoInteractions(streamService);
    }

    // ---------- getHistoricalSessions / searchByTopic ----------

    @Test
    void getHistoricalSessions_shouldReturnPaginatedResults() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        ResearchSession s1 = createMockSession(id1, "Topic A");
        ResearchSession s2 = createMockSession(id2, "Topic B");
        PageImpl<ResearchSession> page = new PageImpl<>(List.of(s2, s1), PageRequest.of(0, 20), 2);
        when(sessionRepo.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(page);

        var result = service.getHistoricalSessions(s -> true, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(2);
    }

    @Test
    void searchByTopic_shouldReturnMatchingSessions() {
        UUID id1 = UUID.randomUUID();
        ResearchSession s1 = createMockSession(id1, "Spring Boot testing");
        PageImpl<ResearchSession> page = new PageImpl<>(List.of(s1), PageRequest.of(0, 20), 1);
        when(sessionRepo.findByTopicContainingIgnoreCase(eq("testing"), any(PageRequest.class))).thenReturn(page);

        var result = service.searchByTopic("testing", PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
    }

    // ---------- submitFollowUp ----------

    @Test
    void submitFollowUp_shouldThrowExceptionForNonCompletedSession() {
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        mockSession.setStatus(ResearchStatus.PROCESSING);
        when(sessionRepo.findByIdWithSteps(sessionId)).thenReturn(mockSession);

        assertThatThrownBy(() -> service.submitFollowUp(sessionId, "What?"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("completed sessions");
    }

    @Test
    void submitFollowUp_shouldThrowExceptionForNullSession() {
        UUID nonExistentId = UUID.randomUUID();
        when(sessionRepo.findByIdWithSteps(nonExistentId)).thenReturn(null);

        assertThatThrownBy(() -> service.submitFollowUp(nonExistentId, "What?"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("completed sessions");
    }

    // ---------- deleteSession ----------

    @Test
    void deleteSession_shouldDeleteNonProcessingSessions() {
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        when(sessionRepo.findByIdWithSteps(sessionId)).thenReturn(mockSession);

        ResearchSession result = service.deleteSession(sessionId);

        assertThat(result).isNotNull();
        verify(sessionRepo).deleteById(sessionId);
    }

    @Test
    void deleteSession_shouldReturnNullForNonExistentSessions() {
        UUID nonExistentId = UUID.randomUUID();
        when(sessionRepo.findByIdWithSteps(nonExistentId)).thenReturn(null);

        ResearchSession result = service.deleteSession(nonExistentId);
        assertThat(result).isNull();
    }

    @Test
    void deleteSession_shouldThrowForProcessingSessions() {
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        mockSession.setStatus(ResearchStatus.PROCESSING);
        when(sessionRepo.findByIdWithSteps(sessionId)).thenReturn(mockSession);

        assertThatThrownBy(() -> service.deleteSession(sessionId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot delete a processing research session");
    }

    // ---------- extractJsonFromMarkdown (private method tested via reflection) ----------

    @Test
    void extractJsonFromMarkdown_shouldExtractJsonFromCodeFence() throws Exception {
        String input = "```json\n[{\"id\": 1, \"title\": \"Sub-topic A\"}]\n```";
        String result = invokeExtractJson(input);
        assertThat(result).isEqualTo("[{\"id\": 1, \"title\": \"Sub-topic A\"}]");
    }

    @Test
    void extractJsonFromMarkdown_shouldReturnEmptyForNull() throws Exception {
        String result = invokeExtractJson(null);
        assertThat(result).isEmpty();
    }

    @Test
    void extractJsonFromMarkdown_shouldStripSurroundingText() throws Exception {
        String input = "Here is your answer:\n\n```json\n[{\"id\": 1, \"title\": \"Sub-topic A\"}]\n```\n\nMore text.";
        String result = invokeExtractJson(input);
        assertThat(result).isEqualTo("[{\"id\": 1, \"title\": \"Sub-topic A\"}]");
    }

    @Test
    void extractJsonFromMarkdown_shouldHandlePlainJsonWithoutFences() throws Exception {
        String input = "[{\"id\": 2, \"title\": \"Direct JSON\"}]";
        String result = invokeExtractJson(input);
        assertThat(result).isEqualTo("[{\"id\": 2, \"title\": \"Direct JSON\"}]");
    }

    private String invokeExtractJson(String input) {
        try {
            Method method = ResearchOrchestratorService.class.getDeclaredMethod("extractJsonFromMarkdown", String.class);
            method.setAccessible(true);
            return (String) method.invoke(service, input);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- helpers ----------

    private ResearchSession createMockSession(UUID id, String topic) {
        ResearchSession session = new ResearchSession();
        try {
            java.lang.reflect.Field idField = ResearchSession.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(session, id);
            java.lang.reflect.Field topicField = ResearchSession.class.getDeclaredField("topic");
            topicField.setAccessible(true);
            topicField.set(session, topic);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return session;
    }
}
