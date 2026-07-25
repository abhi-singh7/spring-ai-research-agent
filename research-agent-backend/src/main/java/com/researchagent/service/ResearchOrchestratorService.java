package com.researchagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchagent.model.dto.FollowUpRequest;
import com.researchagent.model.dto.ResearchRequest;
import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.entity.ResearchStep;
import com.researchagent.model.enums.ResearchStatus;
import com.researchagent.model.enums.StepType;
import com.researchagent.repository.ResearchSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class ResearchOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(ResearchOrchestratorService.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final ResearchSessionRepository sessionRepo;
    private final ResearchStreamingService streamService;
    private final Executor researchTaskExecutor;

    public ResearchOrchestratorService(ChatClient chatClient, ObjectMapper objectMapper,
                                       ResearchSessionRepository sessionRepo,
                                       ResearchStreamingService streamService,
                                       @org.springframework.beans.factory.annotation.Qualifier("researchTaskExecutor") Executor researchTaskExecutor) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.sessionRepo = sessionRepo;
        this.streamService = streamService;
        this.researchTaskExecutor = researchTaskExecutor;
    }

    /**
     * Create a ResearchSession synchronously and kick off the async research process.
     */
    public ResearchSession createAndStart(ResearchRequest request) {
        // 1. Create and persist the session synchronously (cascade will save steps too)
        ResearchSession session = new ResearchSession();
        session.setTopic(request.getTopic());
        session.setStatus(ResearchStatus.PROCESSING);

        sessionRepo.save(session);

        log.info("Created research session: {}", session.getId());
        return session;
    }

    /**
     * Process the research asynchronously after the session has been created.
     */
    public void processResearchAsync(UUID sessionId, ResearchRequest request) {
        // Fetch session from DB (it's persisted now)
        ResearchSession session = sessionRepo.findById(sessionId).orElse(null);
        if (session == null) {
            log.error("Session {} not found for async processing", sessionId);
            return;
        }

        try {
            int subTopicCount = request.getSubTopicCount() != null ? request.getSubTopicCount() : 5;

            // --- Step 1: Break down topic into sub-topics ---
            String breakdownPrompt = """
                You are a research assistant. Given a research topic, break it down into %d distinct
                sub-topics for thorough investigation. Return ONLY a JSON array with this structure:
                [{"id": 1, "title": "...", "description": "..."}, ...]
                Topic: %s
                """.formatted(subTopicCount, request.getTopic());

            String breakdownResult = chatClient.prompt()
                    .system(breakdownPrompt)
                    .user(request.getTopic())
                    .call()
                    .content();

            // Parse sub-topics from the LLM response (extract JSON, stripping markdown code fences and any surrounding text)
            String jsonContent = extractJsonFromMarkdown(breakdownResult);

            // Parse sub-topics from the LLM response
            List<SubTopic> subTopics;
            try {
                subTopics = objectMapper.readValue(jsonContent, new TypeReference<List<SubTopic>>() {});
            } catch (Exception e) {
                log.error("Failed to parse sub-topic breakdown: {}", jsonContent, e);
                // Fallback: create a single generic sub-topic from the response
                String fallbackTitle = request.getTopic();
                subTopics = List.of(new SubTopic(1, fallbackTitle, jsonContent));
            }

            // Save BREAKDOWN step and persist immediately so REST API returns real-time progress
            ResearchStep breakdownStep = saveStep(session, 0, StepType.BREAKDOWN, "COMPLETED", breakdownResult);
            session.addStep(breakdownStep);
            streamService.sendProgress(sessionId, "Topic broken down into " + subTopics.size() + " sub-topics");
            sessionRepo.save(session); // Persist BREAKDOWN step

            log.info("Breakdown complete: {} sub-topics found for session {}", subTopics.size(), sessionId);

            // --- Step 2: Process each sub-topic with tool calling enabled ---
            List<String> allFindings = new java.util.ArrayList<>();
            int maxIterations = request.getMaxIterations() != null ? request.getMaxIterations() : 10;

            for (int i = 0; i < Math.min(subTopics.size(), maxIterations); i++) {
                SubTopic subTopic = subTopics.get(i);

                String subPrompt = """
                    You are a research assistant. Follow these steps for your assigned topic:

                    1. Use the search tool to find web pages about this topic
                    2. For each relevant URL returned by search, use the read_url tool to fetch full page content
                    3. Synthesize all gathered information into a structured summary with key points, evidence, and references

                    Always use both tools — search alone only returns snippets. You MUST call read_url for any URLs that look relevant before synthesizing your answer.
                    """;

                streamService.sendProgress(sessionId, "Researching: " + subTopic.getTitle());

                String response = chatClient.prompt()
                        .system(subPrompt)
                        .user("Research this sub-topic: " + subTopic.getTitle() + "\n" + subTopic.getDescription())
                        .call()
                        .content();

                allFindings.add("## Sub-Topic: " + subTopic.getTitle() + "\n\n" + response);

                ResearchStep subtopicStep = saveStep(session, i + 1, StepType.SUBTOPIC, "COMPLETED", response);
                session.addStep(subtopicStep);
                streamService.sendProgress(sessionId, "Completed research on: " + subTopic.getTitle());
                sessionRepo.save(session); // Persist each SUBTOPIC step incrementally
            }

            // --- Step 3: Generate final report (with streaming) ---
            String synthesisPrompt = """
                You are an expert research analyst. Synthesize the collected findings into a comprehensive,
                well-structured research report with the following sections:
                - Executive Summary
                - Introduction & Background
                - Main Findings (organized by sub-topic)
                - Key Insights & Analysis
                - Conclusion

                Use markdown formatting for headings, lists, and emphasis. Be thorough but concise.
                """;

            String synthesisInput = "## Collected Sub-Topic Findings\n\n" + String.join("\n\n---\n\n", allFindings);

            log.info("Starting final report generation for session {}", sessionId);
            streamService.sendReportStart(sessionId);

            // Use AtomicReference to capture the full report content during streaming
            java.util.concurrent.atomic.AtomicReference<StringBuilder> reportBuffer = new java.util.concurrent.atomic.AtomicReference<>(new StringBuilder());

            // Capture subTopics size before it could be modified by other code paths
            final int topicCount = subTopics.size();

            // Stream the final report back to the frontend and accumulate content
            chatClient.prompt()
                    .system(synthesisPrompt)
                    .user(synthesisInput)
                    .stream()
                    .content()  // returns Flux<String> of string chunks
                    .doOnNext(chunk -> {
                        streamService.sendReportChunk(sessionId, chunk);
                        reportBuffer.getAndUpdate(sb -> sb.append(chunk));  // Accumulate for saving after streaming completes
                    })
                    .subscribe(
                            null,  // onNext already handled above via doOnNext
                            error -> {
                                log.error("Error during report streaming", error);
                                session.fail("Failed to generate final report: " + error.getMessage());
                                streamService.sendError(sessionId, "Failed to generate final report: " + error.getMessage());
                            },
                            () -> {
                                // Report generation complete - update session status with full content
                                String fullReport = reportBuffer.get().toString();
                                session.setFinalReport(fullReport);  // Also set finalReport on the entity
                                session.complete();
                                session.addStep(saveStep(session, topicCount + 1, StepType.FINAL_REPORT, "COMPLETED", fullReport));
                                sessionRepo.save(session); // Cascade saves steps too

                                // Send final REPORT_DONE event with the complete report
                                streamService.sendReportDone(sessionId, fullReport);
                                streamService.sendProgress(sessionId, "Research complete!");
                            }
                    );

        } catch (Exception e) {
            log.error("Error during research orchestration for session {}", sessionId, e);
            session.fail(e.getMessage());
            session.addStep(saveStep(session, 0, StepType.BREAKDOWN, "FAILED", null));
            sessionRepo.save(session); // Cascade saves steps too
            streamService.sendError(sessionId, "Research failed: " + e.getMessage());
        }
    }

    /**
     * Get a research session by ID (with eager fetch of steps).
     */
    @Transactional(readOnly = true)
    public ResearchSession getResearch(UUID sessionId) {
        return sessionRepo.findByIdWithSteps(sessionId);
    }

    /**
     * Cancel a running research session.
     */
    public void cancelResearch(UUID sessionId) {
        ResearchSession session = sessionRepo.findByIdWithSteps(sessionId);
        if (session != null && "PROCESSING".equals(session.getStatus().name())) {
            session.fail("Cancelled by user");
            streamService.sendProgress(sessionId, "Research cancelled by user");
        }
    }

    /**
     * Get historical research sessions with pagination.
     */
    @Transactional(readOnly = true)
    public Page<ResearchSession> getHistoricalSessions(java.util.function.Predicate<ResearchSession> filter,
                                                       org.springframework.data.domain.PageRequest pageable) {
        // Ensure this repository read executes inside a Spring-managed transaction so JDBC
        // connections have autocommit disabled while LOBs are accessed. This helps avoid
        // "Large Objects may not be used in auto-commit mode" when a driver/DB returns
        // Clob instances that rely on the PostgreSQL Large Object API.
        return sessionRepo.findAllByOrderByCreatedAtDesc(pageable);
    }

    /**
     * Search historical sessions by topic (case-insensitive).
     */
    public Page<ResearchSession> searchByTopic(String query, org.springframework.data.domain.PageRequest pageable) {
        return sessionRepo.findByTopicContainingIgnoreCase(query, pageable);
    }

    /**
     * Submit a follow-up question for a completed research session.
     */
    public String submitFollowUp(UUID sessionId, String question) {
        ResearchSession session = getResearch(sessionId);
        if (session == null || !"COMPLETED".equals(session.getStatus().name())) {
            throw new IllegalArgumentException("Can only ask follow-up questions for completed sessions");
        }

        // Use the LLM to answer based on the final report content
        String prompt = """
            You are a research assistant. The user has asked a follow-up question about a previous
            research session. Answer based on the following research findings:

            Topic: %s
            Final Report: %s

            Follow-up Question: %s

            Provide a concise, well-reasoned answer based on the above content. If you cannot find
            relevant information to answer the question, say so clearly.
            """.formatted(session.getTopic(), session.getFinalReport() != null ? session.getFinalReport() : "No report available", question);

        try {
            return chatClient.prompt()
                    .system(prompt)
                    .user("")  // No additional user message needed since prompt covers it all
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Error processing follow-up for session {}", sessionId, e);
            throw new RuntimeException("Failed to process follow-up: " + e.getMessage());
        }
    }

    /**
     * Save a step for the given session. The caller must save the session via cascade.
     */
    private ResearchStep saveStep(ResearchSession session, int orderIndex, StepType type, String status, String content) {
        ResearchStep step = new ResearchStep();
        step.setOrderIndex(orderIndex);
        step.setType(type);
        step.setStatus(status);
        step.setContent(content);
        return step;
    }

    /**
     * Extract JSON content from LLM responses that wrap it in markdown code fences.
     * Handles cases where the LLM adds explanatory text before/after the fence.
     */
    private String extractJsonFromMarkdown(String content) {
        if (content == null) {
            return "";
        }

        // Try to find JSON inside ```json ... ``` or ``` ... ``` fences first
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("```\\w*\\s*(\\[{].+[}\\]]+)\\s*```", java.util.regex.Pattern.DOTALL).matcher(content);
        if (matcher.find()) {
            String json = matcher.group(1).trim();
            // Validate it looks like JSON by checking first char is [ or {
            if (!json.isEmpty() && (json.charAt(0) == '[' || json.charAt(0) == '{')) {
                return json;
            }
        }

        // Fallback: strip fences from the raw content (original behavior for edge cases)
        String trimmed = content.trim();

        // Remove opening fence: ``` or ```json or ```java etc.
        trimmed = trimmed.replaceAll("^```\\w*\\s*\n?", "");

        // Remove closing fence: ``` possibly preceded by whitespace/newline
        trimmed = trimmed.replaceAll("\\n?```$", "");

        return trimmed.trim();
    }

    /**
     * Inner class for sub-topic representation.
     */
    private static class SubTopic {
        private int id;
        private String title;
        private String description;

        public SubTopic() {}

        public SubTopic(int id, String title, String description) {
            this.id = id;
            this.title = title;
            this.description = description;
        }

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}
