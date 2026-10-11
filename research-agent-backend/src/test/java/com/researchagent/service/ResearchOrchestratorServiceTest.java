package com.researchagent.service;

import com.researchagent.model.dto.ResearchRequest;
import com.researchagent.model.dto.SubTopic;
import com.researchagent.model.entity.FollowUpExchange;
import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.entity.ResearchStep;
import com.researchagent.model.enums.ResearchStatus;
import com.researchagent.model.enums.StepType;
import com.researchagent.repository.ResearchSessionRepository;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;
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
    @org.mockito.Spy private ObjectMapper objectMapper = new ObjectMapper();

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

    // ---------- resume (FAILED / CANCELLED sessions) ----------

    @Test
    void resumeResearch_shouldThrowWhenClaimFails() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepo.markResumableIfTerminal(eq(sessionId), any(LocalDateTime.class))).thenReturn(0);

        assertThatThrownBy(() -> service.resumeResearch(sessionId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not in a resumable state");

        // No pipeline work may start when the atomic claim is lost.
        verify(cancellationRegistry, never()).register(any(UUID.class));
    }

    @Test
    void resumeResearch_shouldClaimTerminalSessionAndHandOffToAsyncPipeline() {
        UUID sessionId = UUID.randomUUID();
        ResearchSession session = createMockSession(sessionId, "Topic A");
        session.setStatus(ResearchStatus.PROCESSING);

        when(sessionRepo.markResumableIfTerminal(eq(sessionId), any(LocalDateTime.class))).thenReturn(1);
        // First call (claim read-back) returns the session; second call (async start) returns null → clean abort.
        when(sessionRepo.findByIdWithSteps(sessionId)).thenReturn(session, null);
        ResearchCancellationRegistry.CancellationHandle handle = mock(ResearchCancellationRegistry.CancellationHandle.class);
        when(cancellationRegistry.register(sessionId)).thenReturn(handle);

        ResearchSession result = service.resumeResearch(sessionId);

        assertThat(result).isSameAs(session);
        verify(cancellationRegistry).unregister(sessionId, handle);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recoverPlan_shouldParseStoredBreakdownStep() {
        ResearchSession session = new ResearchSession();
        session.addStep(breakdownStep("""
                {"topic":"T","subTopics":[
                  {"id":1,"title":"Alpha","description":"d1","searchQueries":["q1"]},
                  {"id":2,"title":"Beta","description":"","searchQueries":[]}
                ]}"""));

        List<SubTopic> plan = (List<SubTopic>) ReflectionTestUtils.invokeMethod(service, "recoverPlan", session);

        assertThat(plan).hasSize(2);
        assertThat(plan.get(0).title()).isEqualTo("Alpha");
        assertThat(plan.get(1).title()).isEqualTo("Beta");
    }

    @Test
    @SuppressWarnings("unchecked")
    void recoverPlan_shouldReturnEmptyWhenBreakdownMissingOrUnparseable() {
        ResearchSession noBreakdown = new ResearchSession();
        noBreakdown.addStep(step(StepType.SUBTOPIC, 1, "COMPLETED", "note"));

        ResearchSession badJson = new ResearchSession();
        badJson.addStep(breakdownStep("this is not json"));

        assertThat((List<SubTopic>) ReflectionTestUtils.invokeMethod(service, "recoverPlan", noBreakdown)).isEmpty();
        assertThat((List<SubTopic>) ReflectionTestUtils.invokeMethod(service, "recoverPlan", badJson)).isEmpty();
    }

    @Test
    void upsertSubTopicStep_shouldUpdateExistingStepInPlace() {
        ResearchSession session = new ResearchSession();
        ResearchStep existing = step(StepType.SUBTOPIC, 1, "FAILED", null);
        session.addStep(existing);

        ReflectionTestUtils.invokeMethod(service, "upsertSubTopicStep", session, 1, "COMPLETED", "new note");

        assertThat(session.getSteps()).hasSize(1);
        assertThat(existing.getStatus()).isEqualTo("COMPLETED");
        assertThat(existing.getContent()).isEqualTo("new note");
    }

    @Test
    void upsertSubTopicStep_shouldAppendWhenAbsent() {
        ResearchSession session = new ResearchSession();

        ReflectionTestUtils.invokeMethod(service, "upsertSubTopicStep", session, 2, "COMPLETED", "note");

        assertThat(session.getSteps()).hasSize(1);
        assertThat(session.getSteps().get(0).getType()).isEqualTo(StepType.SUBTOPIC);
        assertThat(session.getSteps().get(0).getOrderIndex()).isEqualTo(2);
    }

    @Test
    void processResumeAsync_shouldReuseCompletedSubTopicAndGenerateReport() {
        UUID sessionId = UUID.randomUUID();
        ResearchSession session = createMockSession(sessionId, "Test topic");
        session.setStatus(ResearchStatus.PROCESSING);
        session.addStep(breakdownStep("""
                {"topic":"Test topic","subTopics":[{"id":1,"title":"Alpha","description":"d","searchQueries":["q"]}]}"""));
        String note = "Findings about Alpha.\n\n## Sources\n- Some Title — https://example.com/a";
        session.addStep(step(StepType.SUBTOPIC, 1, "COMPLETED", note));

        when(sessionRepo.findByIdWithSteps(sessionId)).thenReturn(session);
        when(sessionRepo.findById(sessionId)).thenReturn(java.util.Optional.of(session));
        ResearchCancellationRegistry.CancellationHandle handle = mock(ResearchCancellationRegistry.CancellationHandle.class);
        when(cancellationRegistry.register(sessionId)).thenReturn(handle);
        when(sessionRepo.save(any(ResearchSession.class))).thenAnswer(inv -> inv.getArgument(0));
        ReflectionTestUtils.setField(service, "qualityEvalEnabled", false);
        when(llmGateway.streamComplete(eq(sessionId), anyString(), anyString(), anyDouble()))
            .thenReturn(Flux.just("# Final Report"));

        service.processResumeAsync(sessionId);

        // The reused sub-topic must not trigger any research LLM call; the report stream completes the session.
        verify(llmGateway, never()).completeStructured(any(UUID.class), anyString(), anyString(), anyDouble(), any(Class.class));
        assertThat(session.getStatus()).isEqualTo(ResearchStatus.COMPLETED);
        assertThat(session.getFinalReport()).isEqualTo("# Final Report");
    }

    @Test
    void processResumeAsync_shouldRereearchFailedSubTopicInPlace() {
        UUID sessionId = UUID.randomUUID();
        ResearchSession session = createMockSession(sessionId, "Test topic");
        session.setStatus(ResearchStatus.PROCESSING);
        session.addStep(breakdownStep("""
                {"topic":"Test topic","subTopics":[
                  {"id":1,"title":"Alpha","description":"d","searchQueries":["q"]},
                  {"id":2,"title":"Beta","description":"d","searchQueries":["q"]}
                ]}"""));
        session.addStep(step(StepType.SUBTOPIC, 1, "COMPLETED", "Alpha note.\n\n## Sources\n- A — https://a.example/1"));
        ResearchStep failedBeta = step(StepType.SUBTOPIC, 2, "FAILED", null);
        session.addStep(failedBeta);

        when(sessionRepo.findByIdWithSteps(sessionId)).thenReturn(session);
        when(sessionRepo.findById(sessionId)).thenReturn(java.util.Optional.of(session));
        ResearchCancellationRegistry.CancellationHandle handle = mock(ResearchCancellationRegistry.CancellationHandle.class);
        when(cancellationRegistry.register(sessionId)).thenReturn(handle);
        when(sessionRepo.save(any(ResearchSession.class))).thenAnswer(inv -> inv.getArgument(0));
        ReflectionTestUtils.setField(service, "defaultMaxIterations", 1);
        ReflectionTestUtils.setField(service, "qualityEvalEnabled", false);
        // Only the failed sub-topic (Beta) needs research; Alpha is reused.
        com.researchagent.model.dto.ResearchRoundNote betaNote = new com.researchagent.model.dto.ResearchRoundNote(
                List.of("Beta findings."),
                List.of(new com.researchagent.model.dto.ResearchRoundNote.SourceRef("B", "https://b.example/2")),
                List.of());
        when(llmGateway.completeStructured(eq(sessionId), anyString(), anyString(), anyDouble(), eq(com.researchagent.model.dto.ResearchRoundNote.class)))
            .thenReturn(betaNote);
        when(llmGateway.streamComplete(eq(sessionId), anyString(), anyString(), anyDouble()))
            .thenReturn(Flux.just("# Final Report"));

        service.processResumeAsync(sessionId);

        // The failed step is updated IN PLACE at its original orderIndex — no duplicate steps.
        assertThat(session.getSteps()).anySatisfy(s -> {
            if (s.getType() == StepType.SUBTOPIC && s.getOrderIndex() == 2) {
                assertThat(s.getStatus()).isEqualTo("COMPLETED");
                assertThat(s.getContent()).contains("Beta findings");
            }
        });
        long betaSteps = session.getSteps().stream()
                .filter(s -> s.getType() == StepType.SUBTOPIC && s.getOrderIndex() == 2).count();
        assertThat(betaSteps).isEqualTo(1);
        // Beta was researched exactly once (single round).
        verify(llmGateway, times(1)).completeStructured(eq(sessionId), anyString(), anyString(), anyDouble(), eq(com.researchagent.model.dto.ResearchRoundNote.class));
        assertThat(session.getStatus()).isEqualTo(ResearchStatus.COMPLETED);
    }

    // ---------- helpers ----------

    private ResearchStep step(StepType type, int orderIndex, String status, String content) {
        ResearchStep s = new ResearchStep();
        s.setType(type);
        s.setOrderIndex(orderIndex);
        s.setStatus(status);
        s.setContent(content);
        return s;
    }

    private ResearchStep breakdownStep(String content) {
        return step(StepType.BREAKDOWN, 0, "COMPLETED", content);
    }

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
