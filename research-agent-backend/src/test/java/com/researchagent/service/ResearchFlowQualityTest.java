package com.researchagent.service;

import com.researchagent.model.dto.ResearchRequest;
import com.researchagent.model.dto.ResearchRoundNote;
import com.researchagent.model.dto.ResearchRoundNote.SourceRef;
import com.researchagent.model.dto.SubTopic;
import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.enums.ResearchStatus;
import com.researchagent.repository.ResearchSessionRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavior tests for the quality-enhanced research pipeline (iterative rounds, resilience, source capture).
 * The LLM is mocked at the {@link LlmGateway} seam — no Spring context needed. Structured completions
 * ({@code completeStructured}) stand in for Spring AI's schema-generated prompts: the breakdown returns a
 * typed {@code List<SubTopic>}, research rounds return a typed {@link ResearchRoundNote}.
 */
class ResearchFlowQualityTest {

    private LlmGateway llmGateway;
    private ResearchSessionRepository sessionRepo;
    private ResearchStreamingService streamService;
    private ResearchCancellationRegistry cancellationRegistry;
    private ResearchOrchestratorService service;
    private ResearchSession session;

    @BeforeEach
    void setUp() {
        llmGateway = mock(LlmGateway.class);
        sessionRepo = mock(ResearchSessionRepository.class);
        streamService = mock(ResearchStreamingService.class);
        when(sessionRepo.save(any(ResearchSession.class))).thenAnswer(inv -> inv.getArgument(0));

        cancellationRegistry = new ResearchCancellationRegistry(); // the real registry — tests drive actual cancellations
        service = new ResearchOrchestratorService(llmGateway, new ObjectMapper(), sessionRepo,
                streamService, (Runnable r) -> { /* unused by processResearchAsync */ }, cancellationRegistry);

        session = new ResearchSession();
        session.setId(UUID.randomUUID()); // manually-constructed sessions have no generated id
        session.setTopic("test topic");
        session.setStatus(ResearchStatus.PROCESSING);
        // processResearchAsync loads with the steps collection fetch-joined (async thread has no ambient session)
        when(sessionRepo.findByIdWithSteps(any(UUID.class))).thenReturn(session);
        // Terminal-state guards read the row via findById — point it at the same in-memory entity.
        when(sessionRepo.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(session));
    }

    // ---------- fixtures ----------

    private static SubTopic subTopic(int id, String title) {
        return new SubTopic(id, title, "desc-" + id, List.of("q" + id));
    }

    /** A note that satisfies the coverage heuristic: >=400 chars and 3 distinct source URLs. */
    private static ResearchRoundNote coveredNote() {
        List<String> findings = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            findings.add("Finding number " + i + ": a substantive claim that is supported by the sources read this round.");
        }
        return new ResearchRoundNote(findings,
                List.of(new SourceRef("Survey paper", "https://example.com/paper-one"),
                        new SourceRef("Vendor docs", "https://docs.example.org/two"),
                        new SourceRef("Academic study", "https://academic.edu/three")),
                List.of("none"));
    }

    /** A thin note: short and with a single source, so another round is warranted. */
    private static ResearchRoundNote thinNote() {
        return new ResearchRoundNote(List.of("Early signal only."),
                List.of(new SourceRef("First hit", "https://a.io/1")),
                List.of("depth missing"));
    }

    private ResearchRequest request(int maxIterations) {
        ResearchRequest request = new ResearchRequest();
        request.setTopic(session.getTopic());
        request.setMaxIterations(maxIterations);
        return request;
    }

    // ---------- tests ----------

    @Test
    void happyPath_completesSession_andReferencesBuiltFromCapturedSources() {
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(subTopic(1, "Subtopic 1"), subTopic(2, "Subtopic 2")));
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(ResearchRoundNote.class)))
                .thenReturn(coveredNote());
        when(llmGateway.streamComplete(any(UUID.class), anyString(), anyString(), eq(ResearchOrchestratorService.TEMP_SYNTHESIS)))
                .thenReturn(Flux.just("Hello ", "world"));

        service.processResearchAsync(session.getId(), request(3));

        assertEquals(ResearchStatus.COMPLETED, session.getStatus());
        assertEquals("Hello world", session.getFinalReport());

        ArgumentCaptor<String> synthesisInput = ArgumentCaptor.forClass(String.class);
        verify(llmGateway).streamComplete(any(UUID.class), anyString(), synthesisInput.capture(), any(Double.class));
        String userMsg = synthesisInput.getValue();
        assertTrue(userMsg.contains("Aggregated Source List"));
        assertTrue(userMsg.contains("https://example.com/paper-one"));
        assertTrue(userMsg.contains("https://docs.example.org/two"));
        assertTrue(userMsg.contains("https://academic.edu/three"));
        assertTrue(userMsg.contains("Subtopic 1") && userMsg.contains("Subtopic 2"));

        verify(streamService).sendReportDone(eq(session.getId()), eq("Hello world"));
    }

    @Test
    void breakdown_retriesOnUnconvertibleOutput_thenSucceeds() {
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), any(ParameterizedTypeReference.class)))
                .thenThrow(new IllegalStateException("model returned prose instead of JSON"))
                .thenReturn(List.of(subTopic(1, "Subtopic 1")));
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(ResearchRoundNote.class)))
                .thenReturn(coveredNote());
        when(llmGateway.streamComplete(any(UUID.class), anyString(), anyString(), eq(ResearchOrchestratorService.TEMP_SYNTHESIS)))
                .thenReturn(Flux.just("ok"));

        service.processResearchAsync(session.getId(), request(1));

        assertEquals(ResearchStatus.COMPLETED, session.getStatus());
        // 1 failed structured breakdown attempt + 1 successful attempt + 1 research round = exactly 3 LLM calls
        verify(llmGateway, times(2)).completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), any(ParameterizedTypeReference.class));
        verify(llmGateway, times(1)).completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(ResearchRoundNote.class));
        verify(llmGateway, never()).complete(any(UUID.class), anyString(), anyString(), any(Double.class));
    }

    @Test
    void singleSubTopicFailure_sessionStillCompletes_andGapsDocumented() {
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(subTopic(1, "Subtopic 1"), subTopic(2, "Subtopic 2")));
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(ResearchRoundNote.class))).thenAnswer(inv -> {
            String user = inv.getArgument(2);
            if (user.contains("Subtopic 1")) {
                throw new RuntimeException("LLM down"); // fails all retries
            }
            return coveredNote();
        });
        when(llmGateway.complete(any(UUID.class), anyString(), anyString(), any(Double.class))).thenAnswer(inv -> {
            String user = inv.getArgument(2);
            if (user.contains("Subtopic 1")) {
                throw new RuntimeException("LLM down"); // free-form fallback attempts also fail
            }
            return "free-form note";
        });
        when(llmGateway.streamComplete(any(UUID.class), anyString(), anyString(), eq(ResearchOrchestratorService.TEMP_SYNTHESIS)))
                .thenReturn(Flux.just("partial report"));

        service.processResearchAsync(session.getId(), request(3));

        assertEquals(ResearchStatus.COMPLETED, session.getStatus());
        assertEquals("partial report", session.getFinalReport());

        ArgumentCaptor<String> synthesisInput = ArgumentCaptor.forClass(String.class);
        verify(llmGateway).streamComplete(any(UUID.class), anyString(), synthesisInput.capture(), any(Double.class));
        assertTrue(synthesisInput.getValue().contains("Failed Sub-topics"));
        assertTrue(synthesisInput.getValue().contains("Subtopic 1"));

        // Breakdown(1) + subtopic-1: structured attempt(1) + free-form fallbacks(2) + subtopic-2 round(1) = 5 LLM calls total.
        verify(llmGateway, times(1)).completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), any(ParameterizedTypeReference.class));
        verify(llmGateway, times(2)).completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(ResearchRoundNote.class));
        verify(llmGateway, times(2)).complete(any(UUID.class), anyString(), anyString(), any(Double.class));
    }

    @Test
    void allSubTopicsFail_sessionFailsAndErrorEmitted() {
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(subTopic(1, "Subtopic 1")));
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(ResearchRoundNote.class)))
                .thenThrow(new RuntimeException("LLM down"));
        when(llmGateway.complete(any(UUID.class), anyString(), anyString(), any(Double.class)))
                .thenThrow(new RuntimeException("LLM down"));

        service.processResearchAsync(session.getId(), request(2));

        assertEquals(ResearchStatus.FAILED, session.getStatus());
        assertTrue(String.valueOf(session.getFinalReport()).startsWith("Failed:"));
        verify(streamService).sendError(eq(session.getId()), anyString());
        verify(llmGateway, never()).streamComplete(any(UUID.class), anyString(), anyString(), any(Double.class));
    }

    @Test
    void thinRound_triggersSecondResearchRound() {
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(subTopic(1, "Subtopic 1")));
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(ResearchRoundNote.class)))
                .thenReturn(thinNote(), coveredNote()); // round 1: insufficient coverage; round 2: fills the gap
        when(llmGateway.streamComplete(any(UUID.class), anyString(), anyString(), eq(ResearchOrchestratorService.TEMP_SYNTHESIS)))
                .thenReturn(Flux.just("done"));

        service.processResearchAsync(session.getId(), request(2)); // hard cap: at most 2 rounds per sub-topic

        assertEquals(ResearchStatus.COMPLETED, session.getStatus());
        // breakdown + round1 + round2 = exactly 3 structured LLM calls (no extra round after coverage)
        verify(llmGateway, times(1)).completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), any(ParameterizedTypeReference.class));
        verify(llmGateway, times(2)).completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(ResearchRoundNote.class));
        verify(llmGateway, never()).complete(any(UUID.class), anyString(), anyString(), any(Double.class));

        ArgumentCaptor<String> synthesisInput = ArgumentCaptor.forClass(String.class);
        verify(llmGateway).streamComplete(any(UUID.class), anyString(), synthesisInput.capture(), any(Double.class));
        assertTrue(synthesisInput.getValue().contains("Early signal only"),
                "round-1 findings must be carried into the report input");
    }

    @Test
    void persistenceFailure_doesNotEscapeAsyncMethod_andEmitsError() {
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(subTopic(1, "Subtopic 1")));
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(sessionRepo).save(any()); // every save fails

        assertDoesNotThrow(() -> service.processResearchAsync(session.getId(), request(1)));

        assertEquals(ResearchStatus.FAILED, session.getStatus());
        verify(streamService).sendError(eq(session.getId()), anyString());
    }

    @Test
    void bareUrlsInFindings_stillBecomeReferencesEntries() {
        StringBuilder finding = new StringBuilder("Evidence shows that ");
        for (int i = 0; i < 12; i++) { // pad so the coverage heuristic (length >= 400) is satisfied
            finding.append("the claim survives scrutiny from multiple angles and independent measurements, ");
        }
        finding.append("as noted by https://one.io/a and corroborated by https://two.io/b with a follow-up in https://three.edu/c.");

        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(subTopic(1, "Subtopic 1")));
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(ResearchRoundNote.class)))
                .thenReturn(new ResearchRoundNote(List.of(finding.toString()), List.of(), List.of("none")));
        when(llmGateway.streamComplete(any(UUID.class), anyString(), anyString(), eq(ResearchOrchestratorService.TEMP_SYNTHESIS)))
                .thenReturn(Flux.just("ok"));

        service.processResearchAsync(session.getId(), request(1));

        assertEquals(ResearchStatus.COMPLETED, session.getStatus());
        ArgumentCaptor<String> synthesisInput = ArgumentCaptor.forClass(String.class);
        verify(llmGateway).streamComplete(any(UUID.class), anyString(), synthesisInput.capture(), any(Double.class));
        String refs = synthesisInput.getValue();
        assertTrue(refs.contains("https://one.io/a"));
        assertTrue(refs.contains("https://two.io/b"));
        assertTrue(refs.contains("https://three.edu/c"));
    }

    // ---------- cancellation behavior ----------

    @Test
    void cancelDuringResearch_stopsPipeline_andPersistsCancelledAtomically() {
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(subTopic(1, "Subtopic 1"), subTopic(2, "Subtopic 2")));
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(ResearchRoundNote.class))).thenAnswer(inv -> {
            // User cancels while the first research round is in flight.
            service.cancelResearch(session.getId());
            return coveredNote();
        });

        service.processResearchAsync(session.getId(), request(3));

        // The run stops at the next checkpoint: no report generation, and no COMPLETED/FAILED overwrite of the entity —
        // CANCELLED lives in the persisted row via conditional update (called by cancel + re-asserted on abort).
        assertEquals(ResearchStatus.PROCESSING, session.getStatus());
        verify(llmGateway, never()).streamComplete(any(UUID.class), anyString(), anyString(), any(Double.class));
        verify(sessionRepo, atLeastOnce())
                .markCancelledIfProcessing(eq(session.getId()), any(LocalDateTime.class), eq("Cancelled by user"));
    }

    @Test
    void cancelDuringReportStream_disposesSubscription_andSkipsCompletedWrite() {
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(subTopic(1, "Subtopic 1")));
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(ResearchRoundNote.class)))
                .thenReturn(coveredNote());
        AtomicInteger cancellations = new AtomicInteger();
        // A report stream that never completes — like a long synthesis still in flight.
        Flux<String> stuckStream = Flux.create(sink -> sink.onCancel(cancellations::incrementAndGet));
        when(llmGateway.streamComplete(any(UUID.class), anyString(), anyString(), eq(ResearchOrchestratorService.TEMP_SYNTHESIS)))
                .thenReturn(stuckStream);

        service.processResearchAsync(session.getId(), request(1)); // returns while the stream is still active

        assertEquals(0, cancellations.get());                       // nothing cancelled yet — but a handle exists
        verify(streamService, never()).sendReportDone(eq(session.getId()), anyString());

        service.cancelResearch(session.getId());                     // dispose must propagate to the upstream

        assertEquals(1, cancellations.get());
        assertEquals(ResearchStatus.PROCESSING, session.getStatus()); // no COMPLETED overwrite after cancellation
        verify(streamService, never()).sendReportDone(eq(session.getId()), anyString());
    }

    @Test
    void pipelineStartsAfterCancel_abortsBeforeAnyLlmWork() {
        // A cancel persisted before this async pipeline thread even began (startup race).
        session.setStatus(ResearchStatus.CANCELLED);

        service.processResearchAsync(session.getId(), request(3));

        verify(llmGateway, never()).complete(any(UUID.class), anyString(), anyString(), any(Double.class));
        verify(llmGateway, never()).completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(ResearchRoundNote.class));
        verify(llmGateway, never()).completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), any(ParameterizedTypeReference.class));
        verify(streamService, never()).sendProgress(eq(session.getId()), anyString());
    }
}
