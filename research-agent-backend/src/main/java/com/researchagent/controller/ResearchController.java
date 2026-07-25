package com.researchagent.controller;

import com.researchagent.model.dto.ResearchRequest;
import com.researchagent.model.dto.ResearchResponse;
import com.researchagent.model.dto.StepDTO;
import com.researchagent.model.dto.ResearchSessionDetailDTO;
import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.entity.ResearchStep;
import com.researchagent.service.ResearchOrchestratorService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        ResearchSession session = orchestratorService.getResearch(sessionId);
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

        try {
            orchestratorService.deleteSessionsInBulk(sessionIds);
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
        ResearchSession session = orchestratorService.createAndStart(request);

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
     * Get current status of a research session.
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<ResearchResponse> getStatus(@PathVariable UUID sessionId) {
        ResearchSession session = orchestratorService.getResearch(sessionId);
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
        ResearchSession session = orchestratorService.getResearch(sessionId);
        if (session == null || !session.getStatus().name().equals("PROCESSING")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        orchestratorService.cancelResearch(sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get historical research sessions with pagination.
     */
    @GetMapping("/history")
    public ResponseEntity<Page<ResearchSession>> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(orchestratorService.getHistoricalSessions(sessionId -> true, pageable));
    }

    /**
     * Search historical sessions by topic.
     */
    @GetMapping("/history/search")
    public ResponseEntity<Page<ResearchSession>> searchHistory(
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(orchestratorService.searchByTopic(query, pageable));
    }

    /**
     * Get a single historical research session by ID (with steps included).
     */
    @GetMapping("/history/{sessionId}")
    public ResponseEntity<ResearchSessionDetailDTO> getHistoricalSession(@PathVariable UUID sessionId) {
        ResearchSession session = orchestratorService.getResearch(sessionId);
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

        return ResponseEntity.ok(dto);
    }

    /**
     * Submit a follow-up question for a completed research session.
     */
    @PostMapping("/{sessionId}/followup")
    public ResponseEntity<String> submitFollowUp(@PathVariable UUID sessionId,
                                                  @Valid @RequestBody com.researchagent.model.dto.FollowUpRequest request) {
        String answer = orchestratorService.submitFollowUp(sessionId, request.getQuestion());
        return ResponseEntity.ok(answer);
    }

    /**
     * Get the final report for a completed research session.
     */
    @GetMapping("/{sessionId}/report")
    public ResponseEntity<String> getReport(@PathVariable UUID sessionId) {
        ResearchSession session = orchestratorService.getResearch(sessionId);
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
}
