package com.researchagent.service;

import com.researchagent.model.dto.QualityEvalResult;
import com.researchagent.model.dto.ResearchRequest;
import com.researchagent.model.dto.ResearchRoundNote;
import com.researchagent.model.dto.ResearchRoundNote.SourceRef;
import com.researchagent.model.dto.SubTopic;
import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.enums.ResearchStatus;
import com.researchagent.model.enums.StepType;
import com.researchagent.repository.ResearchSessionRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for the research quality evaluation gate (Step 2.5 in the pipeline).
 *
 * <p>The quality eval runs a structured LLM call between sub-topic research and report generation.
 * When the score is below threshold, supplementary research fills gaps before synthesis.</p>
 */
class ResearchQualityEvalTest {

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

        cancellationRegistry = new ResearchCancellationRegistry();
        service = new ResearchOrchestratorService(llmGateway, new ObjectMapper(), sessionRepo,
                streamService, (Runnable r) -> { }, cancellationRegistry);

        session = new ResearchSession();
        session.setId(UUID.randomUUID());
        session.setTopic("test topic");
        session.setStatus(ResearchStatus.PROCESSING);
        when(sessionRepo.findByIdWithSteps(any(UUID.class))).thenReturn(session);
        when(sessionRepo.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(session));
    }

    // ---------- fixtures ----------

    private static SubTopic subTopic(int id, String title) {
        return new SubTopic(id, title, "desc-" + id, List.of("q" + id));
    }

    private static ResearchRoundNote coveredNote() {
        List<String> findings = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            findings.add("Finding number " + i + ": a substantive claim that is supported by the sources read this round.");
        }
        return new ResearchRoundNote(findings,
                List.of(new SourceRef("Paper", "https://example.com/paper"),
                        new SourceRef("Docs", "https://docs.org/page"),
                        new SourceRef("Study", "https://edu.acad/study")),
                List.of("none"));
    }

    private ResearchRequest request() {
        ResearchRequest req = new ResearchRequest();
        req.setTopic(session.getTopic());
        req.setMaxIterations(1);
        return req;
    }

    /** Stub the standard pipeline: breakdown + research rounds. */
    private void stubStandardPipeline() {
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), any(ParameterizedTypeReference.class)))
                .thenReturn(List.of(subTopic(1, "Subtopic 1")));
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(ResearchRoundNote.class)))
                .thenReturn(coveredNote());
        when(llmGateway.streamComplete(any(UUID.class), anyString(), anyString(), eq(ResearchOrchestratorService.TEMP_SYNTHESIS)))
                .thenReturn(Flux.just("final report"));
    }

    // ---------- tests ----------

    @Test
    void qualityEvalPasses_sessionCompletes_withEvalStepPresent() {
        stubStandardPipeline();
        // Quality eval returns a high score → passes.
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(QualityEvalResult.class)))
                .thenReturn(new QualityEvalResult(8, 7, 9, 8, 8, true, List.of(), List.of()));

        service.processResearchAsync(session.getId(), request());

        assertEquals(ResearchStatus.COMPLETED, session.getStatus());

        // Verify a QUALITY_EVAL step was added.
        boolean hasEvalStep = session.getSteps().stream()
                .anyMatch(s -> s.getType() == StepType.QUALITY_EVAL);
        assertTrue(hasEvalStep, "Quality eval step should be present in the session");

        // Verify the eval content contains scores.
        String evalContent = session.getSteps().stream()
                .filter(s -> s.getType() == StepType.QUALITY_EVAL)
                .findFirst().orElseThrow().getContent();
        assertTrue(evalContent.contains("8/10"));
        assertTrue(evalContent.contains("PASSED"));

        // Verify no supplementary research was triggered (no extra structured call for ResearchRoundNote beyond the 1 round).
        verify(llmGateway, times(1)).completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(ResearchRoundNote.class));
    }

    @Test
    void qualityEvalFails_supplementaryResearchRuns_andReportGenerated() {
        stubStandardPipeline();
        // Quality eval returns a low score → fails, triggers supplementary research.
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(QualityEvalResult.class)))
                .thenReturn(new QualityEvalResult(5, 4, 6, 7, 5, false,
                        List.of("Lacks quantitative data", "Missing authoritative sources"),
                        List.of("Add benchmark comparisons", "Include industry reports")));

        // The supplementary research round returns a new covered note.
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(ResearchRoundNote.class)))
                .thenReturn(coveredNote(), coveredNote()); // first call = main round, second = supplementary

        service.processResearchAsync(session.getId(), request());

        assertEquals(ResearchStatus.COMPLETED, session.getStatus());

        // Verify the eval step shows "NEEDS IMPROVEMENT".
        String evalContent = session.getSteps().stream()
                .filter(s -> s.getType() == StepType.QUALITY_EVAL)
                .findFirst().orElseThrow().getContent();
        assertTrue(evalContent.contains("NEEDS IMPROVEMENT"));
        assertTrue(evalContent.contains("Lacks quantitative data"));

        // Verify supplementary research was called (2 ResearchRoundNote calls: 1 main + 1 supplementary).
        verify(llmGateway, times(2)).completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(ResearchRoundNote.class));

        // Verify the synthesis input includes quality feedback.
        ArgumentCaptor<String> synthesisInput = ArgumentCaptor.forClass(String.class);
        verify(llmGateway).streamComplete(any(UUID.class), anyString(), synthesisInput.capture(), any(Double.class));
        assertTrue(synthesisInput.getValue().contains("Quality Evaluation Feedback"));
        assertTrue(synthesisInput.getValue().contains("Lacks quantitative data"));
        assertTrue(synthesisInput.getValue().contains("Supplementary Research"));
    }

    @Test
    void qualityEvalFails_supplementaryResearchFails_reportStillGenerated() {
        stubStandardPipeline();
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(QualityEvalResult.class)))
                .thenReturn(new QualityEvalResult(3, 4, 5, 6, 4, false,
                        List.of("Very shallow"), List.of("Need more depth")));

        // Supplementary research also fails (all attempts throw).
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(ResearchRoundNote.class)))
                .thenReturn(coveredNote())       // main round succeeds
                .thenThrow(new RuntimeException("LLM down")); // supplementary fails

        service.processResearchAsync(session.getId(), request());

        // Pipeline should still complete (supplementary failure is non-fatal).
        assertEquals(ResearchStatus.COMPLETED, session.getStatus());
        verify(llmGateway).streamComplete(any(UUID.class), anyString(), anyString(), eq(ResearchOrchestratorService.TEMP_SYNTHESIS));
    }

    @Test
    void qualityEvalItselfFails_pipelineContinuesWithoutGate() {
        stubStandardPipeline();
        // Quality eval LLM call fails all retries.
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(QualityEvalResult.class)))
                .thenThrow(new RuntimeException("LLM down"));

        service.processResearchAsync(session.getId(), request());

        // Pipeline should still complete — quality eval failure is non-fatal.
        assertEquals(ResearchStatus.COMPLETED, session.getStatus());
        verify(streamService).sendProgress(eq(session.getId()), contains("Quality evaluation skipped"));

        // No QUALITY_EVAL step was added (eval failed before persisting).
        boolean hasEvalStep = session.getSteps().stream()
                .anyMatch(s -> s.getType() == StepType.QUALITY_EVAL);
        assertFalse(hasEvalStep);
    }

    @Test
    void qualityEvalNullResult_defaultsToPass() {
        stubStandardPipeline();
        // LLM returns null (malformed).
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(QualityEvalResult.class)))
                .thenReturn(null);

        service.processResearchAsync(session.getId(), request());

        assertEquals(ResearchStatus.COMPLETED, session.getStatus());
        // Should have passed (null → default pass with score 7).
        String evalContent = session.getSteps().stream()
                .filter(s -> s.getType() == StepType.QUALITY_EVAL)
                .findFirst().orElseThrow().getContent();
        assertTrue(evalContent.contains("PASSED"));
    }

    @Test
    void qualityEvalProgressEvents_emittedInOrder() {
        stubStandardPipeline();
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(QualityEvalResult.class)))
                .thenReturn(new QualityEvalResult(8, 8, 8, 8, 9, true, List.of(), List.of()));

        service.processResearchAsync(session.getId(), request());

        // Verify progress events were sent in the right sequence.
        var inOrder = inOrder(streamService);
        inOrder.verify(streamService).sendProgress(eq(session.getId()), contains("Evaluating research quality"));
        inOrder.verify(streamService).sendProgress(eq(session.getId()), contains("Quality gate passed"));
    }

    @Test
    void synthesisPromptIncludesEvalRecommendationsWhenIssuesPresent() {
        stubStandardPipeline();
        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(QualityEvalResult.class)))
                .thenReturn(new QualityEvalResult(6, 5, 7, 8, 6, false,
                        List.of("Sub-topic X lacks detail"),
                        List.of("Expand on practical examples", "Add industry comparisons")));

        when(llmGateway.completeStructured(any(UUID.class), anyString(), anyString(), any(Double.class), eq(ResearchRoundNote.class)))
                .thenReturn(coveredNote(), coveredNote());

        service.processResearchAsync(session.getId(), request());

        ArgumentCaptor<String> synthesisInput = ArgumentCaptor.forClass(String.class);
        verify(llmGateway).streamComplete(any(UUID.class), anyString(), synthesisInput.capture(), any(Double.class));
        String input = synthesisInput.getValue();
        assertTrue(input.contains("Sub-topic X lacks detail"));
        assertTrue(input.contains("Expand on practical examples"));
        assertTrue(input.contains("descriptive with suitable details"));
    }
}
