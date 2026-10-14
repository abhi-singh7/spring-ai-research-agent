package com.researchagent.controller;

import com.researchagent.model.dto.FollowUpExchangeDTO;
import com.researchagent.model.dto.ResearchRequest;
import com.researchagent.model.dto.ResearchResponse;
import com.researchagent.model.dto.StepDTO;
import com.researchagent.model.dto.ResearchSessionDetailDTO;
import com.researchagent.model.dto.ResearchSessionSummaryDTO;
import com.researchagent.model.entity.FollowUpExchange;
import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.entity.ResearchStep;
import com.researchagent.model.enums.ResearchStatus;
import com.researchagent.service.ResearchOrchestratorService;
import jakarta.validation.Valid;
import com.researchagent.security.CurrentUser;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/research")
public class ResearchController {

    private static final Logger log = LoggerFactory.getLogger(ResearchController.class);
    
    private final ResearchOrchestratorService orchestratorService;

    public ResearchController(ResearchOrchestratorService orchestratorService) {
        this.orchestratorService = orchestratorService;
    }

    /**
     * Delete a single historical research session (and its steps via cascade).
     * Only works on non-running sessions (COMPLETED, FAILED, CANCELLED).
     */
    @DeleteMapping("/history/{sessionId}")
    public ResponseEntity<Void> deleteHistorySession(@PathVariable UUID sessionId) {
        ResearchSession session = ownResearch(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        // Check if session is still processing — should not be deletable via history endpoint
        if ("PROCESSING".equals(session.getStatus().name())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        orchestratorService.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Bulk delete multiple historical research sessions (and their steps via cascade).
     * All-or-nothing rollback — if any session is invalid/processing, none are deleted.
     */
    @PostMapping("/history/bulk-delete")
    public ResponseEntity<Void> bulkDeleteHistorySessions(@RequestBody List<UUID> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        // Sessions owned by someone else are dropped from the batch — like the per-id endpoints, a foreign
        // id is invisible rather than forbidden (openspec/changes/session-ownership).
        List<UUID> mine = sessionIds.stream().filter(this::ownedOrMissing).toList();
        if (mine.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        try {
            orchestratorService.deleteSessionsInBulk(mine);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            log.error("Bulk delete failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    /**
     * Start a new research session. Returns the session with its SSE stream URL immediately.
     */
    @PostMapping
    public ResponseEntity<ResearchResponse> startResearch(@Valid @RequestBody ResearchRequest request) {
        // Create and persist the session synchronously, then kick off async processing
        ResearchSession session = orchestratorService.createAndStart(request, currentUsername());

        // Kick off async research in a separate thread
        UUID sessionId = session.getId();
        orchestratorService.processResearchAsync(sessionId, request);

        ResearchResponse response = new ResearchResponse();
        response.setId(session.getId());
        response.setTopic(session.getTopic());
        response.setStatus(session.getStatus().name());
        // Stream URL will be discovered via SSE endpoint or polling fallback
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Resume a FAILED or CANCELLED research session. The atomic claim flips the session back to
     * PROCESSING (clearing the failure report) and the pipeline continues from the stored steps:
     * completed sub-topics are reused, failed/missing ones are re-researched, then the quality gate
     * and final report run as usual. 409 when the session is COMPLETED or already PROCESSING.
     */
    @PostMapping("/{sessionId}/resume")
    public ResponseEntity<ResearchResponse> resumeResearch(@PathVariable UUID sessionId) {
        ResearchSession session = ownResearch(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        String status = session.getStatus().name();
        if (!"FAILED".equals(status) && !"CANCELLED".equals(status)) {
            // COMPLETED (nothing to resume), PROCESSING/PENDING (already running)
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        try {
            session = orchestratorService.resumeResearch(sessionId);
        } catch (IllegalStateException e) {
            // Lost the atomic claim race — a concurrent resume won between our check and the claim.
            log.info("Resume of {} lost the claim race: {}", sessionId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        ResearchResponse response = new ResearchResponse();
        response.setId(session.getId());
        response.setTopic(session.getTopic());
        response.setStatus(ResearchStatus.PROCESSING.name());
        return ResponseEntity.ok(response);
    }

    /**
     * Get current status of a research session.
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<ResearchResponse> getStatus(@PathVariable UUID sessionId) {
        ResearchSession session = ownResearch(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        ResearchResponse response = new ResearchResponse();
        response.setId(session.getId());
        response.setTopic(session.getTopic());
        response.setStatus(session.getStatus().name());
        response.setFinalReport(session.getFinalReport());
        response.setCreatedAt(session.getCreatedAt());
        response.setCompletedAt(session.getCompletedAt());

        // Include steps if available
        List<StepDTO> steps = session.getSteps() != null ?
                session.getSteps().stream().map(step -> {
                    StepDTO dto = new StepDTO();
                    dto.setOrderIndex(step.getOrderIndex());
                    dto.setType(step.getType());
                    dto.setStatus(step.getStatus());
                    return dto;
                }).toList() : List.of();
        response.setSteps(steps);

        return ResponseEntity.ok(response);
    }

    /**
     * Cancel a running research session.
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> cancelResearch(@PathVariable UUID sessionId) {
        ResearchSession session = ownResearch(sessionId);
        if (session == null || !session.getStatus().name().equals("PROCESSING")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        log.info("Cancelling research session {}", sessionId);
        orchestratorService.cancelResearch(sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get historical research sessions with pagination.
     */
    @GetMapping("/history")
    public ResponseEntity<Page<ResearchSessionSummaryDTO>> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(summarize(orchestratorService.getHistoricalSessions(currentUsername(), pageable)));
    }

    /**
     * Search historical sessions by topic.
     */
    @GetMapping("/history/search")
    public ResponseEntity<Page<ResearchSessionSummaryDTO>> searchHistory(
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(summarize(orchestratorService.searchByTopic(query, currentUsername(), pageable)));
    }

    /**
     * Get a single historical research session by ID (with steps included).
     */
    @GetMapping("/history/{sessionId}")
    public ResponseEntity<ResearchSessionDetailDTO> getHistoricalSession(@PathVariable UUID sessionId) {
        ResearchSession session = ownResearch(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        ResearchSessionDetailDTO dto = new ResearchSessionDetailDTO();
        dto.setId(session.getId());
        dto.setTopic(session.getTopic());
        dto.setStatus(session.getStatus().name());
        dto.setPrompt(session.getPrompt());
        dto.setFinalReport(session.getFinalReport());
        dto.setCreatedAt(session.getCreatedAt());
        dto.setUpdatedAt(session.getUpdatedAt());
        dto.setCompletedAt(session.getCompletedAt());

        // Include steps for the detail view
        List<StepDTO> steps = session.getSteps() != null ?
                session.getSteps().stream().map(step -> {
                    StepDTO stepDto = new StepDTO();
                    stepDto.setOrderIndex(step.getOrderIndex());
                    stepDto.setType(step.getType());
                    stepDto.setStatus(step.getStatus());
                    stepDto.setContent(step.getContent());
                    return stepDto;
                }).toList() : List.of();
        dto.setSteps(steps);

        // Include the stored follow-up thread so the detail view can render it
        dto.setFollowUps(toFollowUpDTOs(session));

        return ResponseEntity.ok(dto);
    }

    /**
     * Submit a follow-up question for a completed research session. The answer is grounded in the
     * full conversation context (topic + report + prior Q&amp;A) and the exchange is persisted on
     * the session document; the stored exchange is returned.
     */
    @PostMapping("/{sessionId}/followup")
    public ResponseEntity<FollowUpExchangeDTO> submitFollowUp(@PathVariable UUID sessionId,
                                                              @Valid @RequestBody com.researchagent.model.dto.FollowUpRequest request) {
        ResearchSession session = ownResearch(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"COMPLETED".equals(session.getStatus().name())) {
            return ResponseEntity.badRequest().build();
        }

        FollowUpExchange exchange = orchestratorService.submitFollowUp(sessionId, request.getQuestion());
        return ResponseEntity.status(HttpStatus.CREATED).body(toFollowUpDTO(exchange));
    }

    /**
     * Get the stored follow-up thread for a session, in chronological order.
     */
    @GetMapping("/{sessionId}/followups")
    public ResponseEntity<List<FollowUpExchangeDTO>> getFollowUps(@PathVariable UUID sessionId) {
        ResearchSession session = ownResearch(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toFollowUpDTOs(session));
    }

    private FollowUpExchangeDTO toFollowUpDTO(FollowUpExchange exchange) {
        FollowUpExchangeDTO dto = new FollowUpExchangeDTO();
        dto.setId(exchange.getId());
        dto.setQuestion(exchange.getQuestion());
        dto.setAnswer(exchange.getAnswer());
        dto.setCreatedAt(exchange.getCreatedAt());
        return dto;
    }

    private List<FollowUpExchangeDTO> toFollowUpDTOs(ResearchSession session) {
        return session.getFollowUps() != null ?
                session.getFollowUps().stream().map(this::toFollowUpDTO).toList() : List.of();
    }

    /**
     * Username of the caller, as recorded by {@link com.researchagent.security.JwtAuthFilter} in the
     * security context ({@link com.researchagent.model.entity.CurrentUser} for real requests; spring-security-test
     * mocks put a plain String principal, which is honoured so generated MVC tests keep working).
     */
    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof CurrentUser currentUser) {
            return currentUser.username();
        }
        // spring-security-test's @WithMockUser puts a plain String principal — honoured so the generated
        // MVC tests keep exercising the authenticated path.
        return principal instanceof String s ? s : null;
    }

    /**
     * Fetch a session for the caller, or {@code null} when it does not exist OR belongs to another user.
     * Callers answer 404 in both cases: a 403 would confirm that the id exists under a different owner,
     * and session ids are handed out by the history list (openspec/changes/session-ownership).
     */
    private ResearchSession ownResearch(UUID sessionId) {
        ResearchSession session = orchestratorService.getResearch(sessionId);
        if (session == null) {
            return null;
        }
        String owner = session.getOwner();
        if (owner != null && !owner.equals(currentUsername())) {
            log.info("Session {} belongs to {}, not to the caller", sessionId, owner);
            return null;
        }
        return session;
    }

    /** True when the id is not another user's session (missing ids stay in the batch so the orchestrator still rejects the request). */
    private boolean ownedOrMissing(UUID sessionId) {
        ResearchSession session = orchestratorService.getResearch(sessionId);
        return ownResearch(sessionId) != null || session == null;
    }

    /**
     * Get the final report for a completed research session.
     */
    @GetMapping("/{sessionId}/report")
    public ResponseEntity<String> getReport(@PathVariable UUID sessionId) {
        ResearchSession session = ownResearch(sessionId);
        if (session == null || !"COMPLETED".equals(session.getStatus().name())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Session not found or not completed");
        }

        String report = session.getFinalReport();
        if (report != null && !report.isEmpty()) {
            return ResponseEntity.ok(report);
        }

        // Try to generate from steps as fallback
        report = generateFromSteps(session);
        if (report != null) {
            return ResponseEntity.ok(report);
        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No report available for this session");
    }

    /**
     * Generate a report from the collected sub-topic findings.
     */
    private String generateFromSteps(ResearchSession session) {
        StringBuilder sb = new StringBuilder();
        List<String> findings = session.getSteps().stream()
                .filter(step -> step.getType().name().equals("SUBTOPIC") && "COMPLETED".equals(step.getStatus()))
                .map(ResearchStep::getContent)
                .toList();

        if (findings.isEmpty()) {
            return null;
        }

        sb.append("# ").append(session.getTopic()).append("\n\n");
        for (String finding : findings) {
            sb.append(finding).append("\n---\n");
        }
        return sb.toString();
    }

    /**
     * History rows are summaries, never entities: {@code ResearchSession} carries the full {@code finalReport}
     * and prompt, which used to be serialized into every row of every page while the list view renders neither.
     * The page window, sort and total count are preserved by {@code Page#map}; only the row payload shrinks
     * (openspec/changes/history-summary-payload).
     */
    private Page<ResearchSessionSummaryDTO> summarize(Page<ResearchSession> page) {
        return page == null ? null : page.map(session -> toSummary(session));
    }

    private ResearchSessionSummaryDTO toSummary(ResearchSession session) {
        ResearchSessionSummaryDTO summary = new ResearchSessionSummaryDTO();
        summary.setId(session.getId());
        summary.setTopic(session.getTopic());
        summary.setStatus(session.getStatus().name());
        summary.setOwner(session.getOwner());
        summary.setCreatedAt(session.getCreatedAt());
        summary.setUpdatedAt(session.getUpdatedAt());
        summary.setCompletedAt(session.getCompletedAt());
        summary.setReportAvailable(session.getFinalReport() != null && !session.getFinalReport().isEmpty());
        summary.setStepCount(session.getSteps() == null ? 0 : session.getSteps().size());
        return summary;
    }
}
