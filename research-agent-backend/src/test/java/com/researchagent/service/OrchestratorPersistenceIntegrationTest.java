package com.researchagent.service;

import com.researchagent.model.dto.ResearchRequest;
import com.researchagent.model.dto.ResearchRoundNote;
import com.researchagent.model.dto.ResearchRoundNote.SourceRef;
import com.researchagent.model.dto.SubTopic;
import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.enums.ResearchStatus;
import com.researchagent.model.enums.StepType;
import com.researchagent.repository.ResearchSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Full-context regression test for the research pipeline's persistence behavior.
 *
 * <p>Runs the REAL orchestrator (including its {@code @Async} proxy) against REAL Spring Data MongoDB on the
 * local test database, with a mocked {@link LlmGateway}. With embedded steps every save() is a whole-document
 * replace, so this guards that repeated incremental saves never duplicate or drop step entries — exactly 4
 * steps with unique sequential order indexes after a full run. The earlier unit tests (mocked repository)
 * cannot catch this; only a real persistence environment can.</p>
 */
@SpringBootTest
class OrchestratorPersistenceIntegrationTest {

    @Autowired
    private ResearchOrchestratorService orchestrator;

    @Autowired
    private ResearchSessionRepository sessionRepo;

    @MockitoBean
    private LlmGateway llmGateway;

    /** The test database persists across runs on the local MongoDB — start each test from a clean slate. */
    @BeforeEach
    void cleanDatabase() {
        sessionRepo.deleteAll();
    }

    /** Planning (breakdown) output: a typed list of 2 sub-topics per the planner's structured-output schema. */
    private static List<SubTopic> breakdownPlan() {
        return List.of(
                new SubTopic(1, "Alpha", "alpha scope", List.of("alpha query")),
                new SubTopic(2, "Beta", "beta scope", List.of("beta query")));
    }

    /** Research note: >400 chars and 3 captured sources → coverage met after a single round. */
    private static ResearchRoundNote researchNote() {
        return new ResearchRoundNote(
                List.of("The topic has been investigated across multiple independent sources. Sources agree on the core facts, with minor differences in emphasis; cross-checking several of them confirms the central claims and provides concrete examples, dates, and figures that support each assertion made here."),
                List.of(new SourceRef("Source One", "http://source1.example.com/a"),
                        new SourceRef("Source Two", "http://source2.example.com/b"),
                        new SourceRef("Source Three", "http://source3.example.com/c")),
                List.of("none"));
    }

    @Test
    void fullResearchFlow_persistsEveryStepWithoutDuplicateKeyViolation() throws InterruptedException {
        // Arrange: a session document as created by createAndStart (PROCESSING, started).
        // The UUID is generated in the entity constructor; save it and read the id back.
        ResearchSession session = new ResearchSession();
        session.setTopic("Integration persistence check");
        session.setStatus(ResearchStatus.PROCESSING);
        sessionRepo.save(session);
        UUID sessionId = session.getId();

        // LLM stubs per phase (temperatures match the service constants: planning 0.3 / research 0.7 / synthesis 0.4)
        when(llmGateway.completeStructured(anyString(), anyString(), eq(0.3), any(ParameterizedTypeReference.class)))
                .thenReturn(breakdownPlan());
        when(llmGateway.completeStructured(anyString(), anyString(), eq(0.7), eq(ResearchRoundNote.class)))
                .thenReturn(researchNote());
        when(llmGateway.streamComplete(anyString(), anyString(), eq(0.4)))
                .thenReturn(reactor.core.publisher.Flux.just("# Final Report\n", "Body text."));

        // Act: real @Async execution on the research executor
        ResearchRequest request = new ResearchRequest();
        request.setTopic("Integration persistence check");
        request.setSubTopicCount(2);
        request.setMaxIterations(1);
        orchestrator.processResearchAsync(sessionId, request);

        // Poll until the async run settles (max 60s; mocked LLM → should finish in well under that)
        ResearchStatus status = ResearchStatus.PROCESSING;
        long deadline = System.currentTimeMillis() + 60_000;
        while ((status == ResearchStatus.PROCESSING || status == ResearchStatus.PENDING) && System.currentTimeMillis() < deadline) {
            Thread.sleep(250);
            status = sessionRepo.findById(sessionId).orElseThrow().getStatus();
        }

        // Assert: completed, and exactly 4 embedded steps with unique order indexes (no duplicates)
        assertEquals(ResearchStatus.COMPLETED, status, "research run must complete without persistence errors");
        ResearchSession finished = sessionRepo.findByIdWithSteps(sessionId);
        List<Integer> orderIndexes = finished.getSteps().stream()
                .map(step -> step.getOrderIndex())
                .sorted()
                .toList();
        assertTrue(orderIndexes.equals(List.of(0,1,2,3)), "unexpected steps: " + finished.getSteps().stream().map(s -> s.getType() + "@" + s.getOrderIndex()).toList());
        assertEquals(List.of(0, 1, 2, 3), orderIndexes, "order indexes must be sequential and unique");
        assertNotNull(finished.getFinalReport());
        assertTrue(finished.getFinalReport().contains("Final Report"));
    }

    /**
     * Cancelling a running pipeline must persist CANCELLED atomically (the conditional updateFirst, which also
     * proves UUID parameters bind correctly) and stop all further work — no report generation after cancel.
     */
    @Test
    void cancelMidPipeline_persistsCancelledAtomically_andStopsRun() throws InterruptedException {
        ResearchSession session = new ResearchSession();
        session.setTopic("Cancel mid-pipeline check");
        session.setStatus(ResearchStatus.PROCESSING);
        sessionRepo.save(session);
        UUID sessionId = session.getId();

        AtomicInteger researchCalls = new AtomicInteger();
        when(llmGateway.completeStructured(anyString(), anyString(), eq(0.3), any(ParameterizedTypeReference.class)))
                .thenReturn(breakdownPlan());
        when(llmGateway.completeStructured(anyString(), anyString(), eq(0.7), eq(ResearchRoundNote.class))).thenAnswer(inv -> {
            if (researchCalls.incrementAndGet() == 1) {
                // User cancels while the first research LLM call is in flight.
                orchestrator.cancelResearch(sessionId);
            }
            return researchNote();
        });

        ResearchRequest request = new ResearchRequest();
        request.setTopic("Cancel mid-pipeline check");
        request.setSubTopicCount(2);
        request.setMaxIterations(1);
        orchestrator.processResearchAsync(sessionId, request);

        // Poll until the run settles (cancellation lands fast — no report generation after it).
        ResearchStatus status = ResearchStatus.PROCESSING;
        long deadline = System.currentTimeMillis() + 60_000;
        while ((status == ResearchStatus.PROCESSING || status == ResearchStatus.PENDING) && System.currentTimeMillis() < deadline) {
            Thread.sleep(250);
            status = sessionRepo.findById(sessionId).orElseThrow().getStatus();
        }

        assertEquals(ResearchStatus.CANCELLED, status, "cancelling a running pipeline must persist CANCELLED");
        ResearchSession cancelled = sessionRepo.findByIdWithSteps(sessionId);
        assertEquals("Cancelled by user", cancelled.getFinalReport());
        // Only the BREAKDOWN step was persisted before the cancel; no sub-topic or report work followed.
        assertTrue(cancelled.getSteps().stream()
                .anyMatch(s -> s.getOrderIndex() == 0 && s.getType() == StepType.BREAKDOWN));
        assertEquals(1, cancelled.getSteps().size(), "no steps may be written after the cancel signal: " +
                cancelled.getSteps().stream().map(s -> s.getType() + "@" + s.getOrderIndex()).toList());
    }
}
