package com.researchagent.service;

import com.researchagent.model.dto.ResearchRequest;
import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.enums.ResearchStatus;
import com.researchagent.repository.ResearchSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Full-context regression test for the research pipeline's persistence behavior.
 *
 * <p>Runs the REAL orchestrator (including its {@code @Async} proxy), REAL Hibernate against H2 with the
 * production schema/constraints (incl. {@code uq_session_order}), and a mocked {@link LlmGateway}. This is
 * what guards against step re-insertion on repeated {@code repo.save(session)} merges: because step ids are
 * DB-generated, re-merging a stale local entity graph makes Hibernate treat already-persisted steps as new
 * children → duplicate-key violation. The earlier unit tests (mocked repository) cannot catch this; only a
 * real JPA environment can.</p>
 */
@SpringBootTest
class OrchestratorPersistenceIntegrationTest {

    @Autowired
    private ResearchOrchestratorService orchestrator;

    @Autowired
    private ResearchSessionRepository sessionRepo;

    @MockBean
    private LlmGateway llmGateway;

    /** Planning (breakdown) output: a JSON ARRAY of 2 sub-topics per the planner schema. */
    private static final String BREAKDOWN_JSON = """
            [
              {"id":1,"title":"Alpha","description":"alpha scope","searchQueries":["alpha query"]},
              {"id":2,"title":"Beta","description":"beta scope","searchQueries":["beta query"]}
            ]""";

    /** Research notes: >400 chars and 3 captured source bullets → coverage met after a single round. */
    private static final String RESEARCH_NOTES = """
            FINDINGS:
            The topic has been investigated across multiple independent sources. Sources agree on the core
            facts, with minor differences in emphasis; cross-checking several of them confirms the central
            claims and provides concrete examples, dates, and figures that support each assertion made here.

            SOURCES:
            - Source One — http://source1.example.com/a
            - Source Two — http://source2.example.com/b
            - Source Three — http://source3.example.com/c""";

    @Test
    void fullResearchFlow_persistsEveryStepWithoutDuplicateKeyViolation() throws InterruptedException {
        // Arrange: a session row as created by createAndStart (PROCESSING, started).
        // Ids are DB-generated (UUIDGenerator): save first WITHOUT setting an id, then read it back —
        // pre-setting an id would make Spring Data take the merge path and fail with a stale-object error.
        ResearchSession session = new ResearchSession();
        session.setTopic("Integration persistence check");
        session.setStatus(ResearchStatus.PROCESSING);
        sessionRepo.save(session);
        UUID sessionId = session.getId();

        // LLM stubs per phase (temperatures match the service constants: planning 0.3 / research 0.7 / synthesis 0.4)
        when(llmGateway.complete(anyString(), anyString(), eq(0.3))).thenReturn(BREAKDOWN_JSON);
        when(llmGateway.complete(anyString(), anyString(), eq(0.7))).thenReturn(RESEARCH_NOTES);
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

        // Assert: completed, and exactly 4 step rows with unique order indexes (no duplicates)
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
}
