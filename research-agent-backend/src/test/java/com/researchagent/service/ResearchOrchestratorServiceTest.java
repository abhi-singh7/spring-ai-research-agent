package com.researchagent.service;

import com.researchagent.model.dto.ResearchRequest;
import com.researchagent.model.entity.FollowUpExchange;
import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.enums.ResearchStatus;
import com.researchagent.repository.ResearchSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResearchOrchestratorServiceTest {

    @Mock private LlmGateway llmGateway;
    @Mock private ResearchSessionRepository sessionRepo;
    @Mock private ResearchStreamingService streamService;
    @Mock private Executor researchTaskExecutor;
    @Mock private ResearchCancellationRegistry cancellationRegistry;

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
    void cancelResearch_shouldAtomicallyPersistCancelledAndNotify() {
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        mockSession.setStatus(ResearchStatus.PROCESSING);
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(mockSession));
        when(cancellationRegistry.cancel(eq(sessionId))).thenReturn(true);
        when(sessionRepo.markCancelledIfProcessing(any(UUID.class), any(LocalDateTime.class), eq("Cancelled by user"))).thenReturn(1);

        service.cancelResearch(sessionId);

        // CANCELLED is persisted with an atomic conditional update (only while still PROCESSING) — never a stale in-memory mutation.
        verify(sessionRepo).markCancelledIfProcessing(eq(sessionId), any(LocalDateTime.class), eq("Cancelled by user"));
        verify(streamService).sendProgress(eq(sessionId), eq("Research cancelled by user"));
    }

    @Test
    void cancelResearch_shouldPersistEvenWithoutInFlightHandle() {
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        mockSession.setStatus(ResearchStatus.PROCESSING);
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(mockSession));
        // No live pipeline handle (e.g. the run just finished) — status is still persisted.
        when(cancellationRegistry.cancel(eq(sessionId))).thenReturn(false);

        service.cancelResearch(sessionId);

        verify(sessionRepo).markCancelledIfProcessing(eq(sessionId), any(LocalDateTime.class), eq("Cancelled by user"));
    }

    @Test
    void cancelResearch_shouldDoNothingForNonProcessingSessions() {
        UUID sessionId = UUID.randomUUID();
        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        mockSession.setStatus(ResearchStatus.COMPLETED);
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(mockSession));

        service.cancelResearch(sessionId);

        assertThat(mockSession.getStatus()).isEqualTo(ResearchStatus.COMPLETED);
        verify(cancellationRegistry, never()).cancel(any());
        verifyNoInteractions(streamService);
    }

    @Test
    void cancelResearch_shouldDoNothingForNullSession() {
        UUID nonExistentId = UUID.randomUUID();
        when(sessionRepo.findById(nonExistentId)).thenReturn(Optional.empty());

        service.cancelResearch(nonExistentId);

        verify(cancellationRegistry, never()).cancel(any());
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

    @Test
    void submitFollowUp_shouldPersistExchangeOnAdoptedSessionWithFullContext() {
        UUID sessionId = UUID.randomUUID();
        ResearchSession session = new ResearchSession();
        session.setId(sessionId);
        session.setStatus(ResearchStatus.COMPLETED);
        session.setTopic("Quantum computing");
        session.setFinalReport("# Quantum computing\nQubits are the basic unit...");

        // A prior exchange that must appear in the prompt as conversation context
        FollowUpExchange prior = new FollowUpExchange();
        prior.setQuestion("What is a qubit?");
        prior.setAnswer("A qubit is the quantum analogue of a bit.");
        session.addFollowUp(prior);

        when(sessionRepo.findByIdWithSteps(sessionId)).thenReturn(session);
        when(llmGateway.complete(eq(sessionId), anyString(), anyString(), eq(ResearchOrchestratorService.TEMP_FOLLOWUP)))
            .thenReturn("Superposition lets a qubit hold 0 and 1 at once.");
        when(sessionRepo.save(any(ResearchSession.class))).thenAnswer(inv -> inv.getArgument(0));

        FollowUpExchange result = service.submitFollowUp(sessionId, "What is superposition?");

        // The returned exchange is the stored one (id + timestamp populated)
        assertThat(result.getId()).isNotNull();
        assertThat(result.getQuestion()).isEqualTo("What is superposition?");
        assertThat(result.getAnswer()).isEqualTo("Superposition lets a qubit hold 0 and 1 at once.");
        assertThat(result.getCreatedAt()).isNotNull();

        // The prompt carries topic, report AND the prior Q&A transcript
        org.mockito.ArgumentCaptor<String> userMsg = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(llmGateway).complete(eq(sessionId), anyString(), userMsg.capture(), eq(ResearchOrchestratorService.TEMP_FOLLOWUP));
        String prompt = userMsg.getValue();
        assertThat(prompt).contains("Quantum computing")
            .contains("Qubits are the basic unit")
            .contains("What is a qubit?")
            .contains("A qubit is the quantum analogue of a bit.")
            .contains("What is superposition?");

        // The exchange was persisted on the session that was saved (adopted instance)
        org.mockito.ArgumentCaptor<ResearchSession> saved = org.mockito.ArgumentCaptor.forClass(ResearchSession.class);
        verify(sessionRepo).save(saved.capture());
        assertThat(saved.getValue().getFollowUps()).hasSize(2);
        assertThat(saved.getValue().getFollowUps().get(1)).isSameAs(result);
    }

    @Test
    void submitFollowUp_shouldNotSaveWhenLlmCallFails() {
        UUID sessionId = UUID.randomUUID();
        ResearchSession session = new ResearchSession();
        session.setId(sessionId);
        session.setStatus(ResearchStatus.COMPLETED);
        session.setTopic("Quantum computing");
        session.setFinalReport("# Quantum computing");

        when(sessionRepo.findByIdWithSteps(sessionId)).thenReturn(session);
        when(llmGateway.complete(eq(sessionId), anyString(), anyString(), eq(ResearchOrchestratorService.TEMP_FOLLOWUP)))
            .thenThrow(new RuntimeException("LLM down"));

        assertThatThrownBy(() -> service.submitFollowUp(sessionId, "What?"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to process follow-up");

        verify(sessionRepo, never()).save(any(ResearchSession.class));
        assertThat(session.getFollowUps()).isEmpty();
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

    // ---------- deleteSessionsInBulk ----------

    @Test
    void deleteSessionsInBulk_shouldDeleteAllValidSessions() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        ResearchSession s1 = createMockSession(id1, "Topic A");
        ResearchSession s2 = createMockSession(id2, "Topic B");

        when(sessionRepo.findByIdWithSteps(id1)).thenReturn(s1);
        when(sessionRepo.findByIdWithSteps(id2)).thenReturn(s2);

        List<ResearchSession> result = service.deleteSessionsInBulk(List.of(id1, id2));

        assertThat(result).hasSize(2);
        // One atomic multi-delete of the whole validated batch (Mongo has no per-row rollback)
        verify(sessionRepo).deleteAllById(List.of(id1, id2));
    }

    @Test
    void deleteSessionsInBulk_shouldThrowWhenAnySessionIsProcessing() {
        UUID validId = UUID.randomUUID();
        UUID processingId = UUID.randomUUID();
        ResearchSession validSession = createMockSession(validId, "Topic A");
        validSession.setStatus(ResearchStatus.COMPLETED);
        ResearchSession processingSession = new ResearchSession();
        processingSession.setId(processingId);
        processingSession.setStatus(ResearchStatus.PROCESSING);

        when(sessionRepo.findByIdWithSteps(validId)).thenReturn(validSession);
        when(sessionRepo.findByIdWithSteps(processingId)).thenReturn(processingSession);

        assertThatThrownBy(() -> service.deleteSessionsInBulk(List.of(validId, processingId)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot delete session");

        verify(sessionRepo, never()).deleteAllById(anyList());
    }

    @Test
    void deleteSessionsInBulk_shouldThrowWhenAnySessionNotFound() {
        UUID validId = UUID.randomUUID();
        UUID notFoundId = UUID.randomUUID();
        ResearchSession validSession = createMockSession(validId, "Topic A");

        when(sessionRepo.findByIdWithSteps(validId)).thenReturn(validSession);
        when(sessionRepo.findByIdWithSteps(notFoundId)).thenReturn(null);

        assertThatThrownBy(() -> service.deleteSessionsInBulk(List.of(validId, notFoundId)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot delete session");

        verify(sessionRepo, never()).deleteAllById(anyList());
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
