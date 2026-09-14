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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ResearchOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(ResearchOrchestratorService.class);

    /** Per-stage sampling temperatures (tuned for local LLMs): planning/synthesis want determinism, research wants breadth. */
    public static final double TEMP_PLANNING = 0.3;
    public static final double TEMP_RESEARCH = 0.7;
    public static final double TEMP_SYNTHESIS = 0.4;

    /** Retry policy for individual LLM calls: 1 initial attempt + 2 retries with short backoff. */
    private static final int MAX_LLM_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 500;

    /** Coverage heuristic — a sub-topic is considered covered once its note cites >= MIN_SOURCES_FOR_COVERAGE distinct URLs and has substantive length. */
    private static final int MIN_SOURCES_FOR_COVERAGE = 3;
    private static final int MIN_NOTE_LENGTH_FOR_COVERAGE = 400;

    /** Fallback for the iterative research rounds when the request does not specify maxIterations. */
    @Value("${app.research.default-max-iterations:3}")
    private int defaultMaxIterations;

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final ResearchSessionRepository sessionRepo;
    private final ResearchStreamingService streamService;
    private final Executor researchTaskExecutor;
    private final ResearchCancellationRegistry cancellationRegistry;

    public ResearchOrchestratorService(LlmGateway llmGateway, ObjectMapper objectMapper,
                                       ResearchSessionRepository sessionRepo,
                                       ResearchStreamingService streamService,
                                       @org.springframework.beans.factory.annotation.Qualifier("researchTaskExecutor") Executor researchTaskExecutor,
                                       ResearchCancellationRegistry cancellationRegistry) {
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
        this.sessionRepo = sessionRepo;
        this.streamService = streamService;
        this.researchTaskExecutor = researchTaskExecutor;
        this.cancellationRegistry = cancellationRegistry;
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

    // ==================== PROMPTS ====================

    private static final String RESEARCH_ROUND_PROMPT = """
            You are a meticulous web researcher inside an automated research pipeline. You have two tools:
            `search` (web search) and `read_url` (fetches full page content). Follow this method for the assigned sub-topic:

            1. Run searches for EVERY provided query, plus useful variants you judge necessary.
            2. Choose results from DIFFERENT independent sources — prefer authoritative domains (official documentation,
               standards bodies, .gov/.edu institutions, reputable publications). Avoid multiple pages from a single site.
            3. Fetch the full content of at least 3 distinct relevant pages with `read_url` before writing findings —
               search snippets alone are not sufficient evidence.
            4. Pay attention to recency where it matters (note dates/versions) and to anything you could NOT verify
               or that conflicts across sources.
            5. If a search returns "No results from any backend", do NOT retry that query or close variants — the tool
               has already escalated through every available engine; continue with the information you have instead.

            Then write your research note in this EXACT format:

            ## Findings
            - Specific, factual key findings as bullet points; attribute important claims inline like (source: <domain>).

            ## Sources Consulted
            - Source title — https://full/url/one        (one line per page you ACTUALLY read; list at least 3)

            ## Open Questions
            - Gaps, conflicts or follow-up searches that remain. Write "None" if coverage is complete.

            Do not include any other sections or text outside this format.
            """;

    private static final String RESEARCH_FOLLOWUP_PROMPT = """
            You are a meticulous web researcher continuing an in-progress investigation. You have two tools:
            `search` (web search) and `read_url` (fetches full page content).

            The research notes for the assigned sub-topic so far are provided in your message, including any open questions.
            Fill ONLY the gaps: run additional targeted searches and page reads that answer the open questions or verify
            conflicting claims. Do not repeat findings already covered. If a search returns "No results from any
            backend", do NOT retry that query — accept the gap and state it under Open Questions instead.

            Output only the NEW content, still using this EXACT format:

            ## Findings
            - New key findings as bullet points; attribute important claims inline like (source: <domain>).

            ## Sources Consulted
            - Source title — https://full/url/one        (only pages you ACTUALLY read in THIS round)

            ## Open Questions
            - Remaining gaps after this round. Write "None" if coverage is now complete.

            If you cannot find new information, state that clearly under ## Findings instead of repeating old content.
            Do not include any other sections or text outside this format.
            """;

    private static final String SYNTHESIS_PROMPT = """
            You are an expert research analyst writing the final deliverable of a web-research pipeline. Write ONE
            comprehensive markdown report from the provided sub-topic notes and source list.

            Required structure (use exactly these section headings):

            ## Executive Summary        — 3–5 sentences on what was found overall
            ## Key Takeaways            — bulleted most important insights
            ## Findings by Sub-Topic    — one subsection per researched sub-topic, synthesizing its note
            ## Cross-Cutting Analysis   — themes, tensions or conflicts ACROSS sources; state explicitly where sources disagree
            ## Research Gaps & Confidence — list any failed/incomplete sub-topics (if provided) and rate overall confidence
                                          qualitatively (High/Medium/Low) with reasons
            ## Conclusion                — final synthesis paragraph(s)
            ## References                — numbered list, "Title — URL", built ONLY from the Aggregated Source List below

            Rules:
            - Base every factual claim on the notes; do not invent facts or numbers.
            - Never cite a URL that is not in the Aggregated Source List. If it is empty, omit the References section
              and note under Research Gaps & Confidence that evidence was limited to search snippets.
            - Be thorough but concise — no filler, no meta-commentary about these instructions.
            """;

    /**
     * Process the research asynchronously after the session has been created.
     *
     * Pipeline: breakdown plan (with per-sub-topic search queries) → iterative research rounds per sub-topic
     * (retries + partial-failure tolerance) → final streamed report whose References section is built from the
     * URLs actually captured during page reads. Partial failures produce a report that documents gaps instead of
     * failing the whole session; only total failure fails the session.
     *
     * <p>Cancellation is cooperative: this run registers a handle with {@link ResearchCancellationRegistry}, which is
     * checked before every sub-topic round and LLM call attempt (an in-flight blocking HTTP call completes, but no
     * further work starts), and holds the active report-stream subscription so it can be disposed mid-flight. Terminal-state
     * writes are guarded by {@link #ownsSessionState(UUID)} so a cancelled run is never overwritten with COMPLETED/FAILED
     * by late pipeline or stream callbacks.</p>
     *
     * <p>Runs on {@code researchTaskExecutor} (Spring proxy) so the POST /api/research call returns immediately;
     * progress flows over SSE. Direct calls in unit tests run synchronously.</p>
     */
    @Async("researchTaskExecutor")
    public void processResearchAsync(UUID sessionId, ResearchRequest request) {
        ResearchSession session = null; // assigned inside the guarded block below

        // Cooperative cancellation handle: stops all subsequent work once a user cancels.
        ResearchCancellationRegistry.CancellationHandle handle = cancellationRegistry.register(sessionId);
        boolean reportStreamStarted = false;
        try {
            // Fetch session with steps FETCH-JOINed: this method runs on a pool thread (and later streaming
            // callbacks run on Reactor threads), so there is NO ambient Hibernate session/OSIV. The lazy `steps`
            // bag must be initialized up front; afterwards addStep()/save() operate on the in-memory collection
            // and each save() opens its own short transaction.
            session = sessionRepo.findByIdWithSteps(sessionId);
            if (session == null) {
                log.error("Session {} not found for async processing", sessionId);
                return;
            }

            // Startup race guard: a cancel may have persisted before this pipeline thread even began. Aborting here
            // keeps the run from doing LLM work and writing its stale PROCESSING row over CANCELLED.
            if (!ownsSessionState(sessionId)) {
                log.warn("Pipeline for {} starting but session is no longer PROCESSING — aborting without further work", sessionId);
                return;
            }

            int subTopicCount = request.getSubTopicCount() != null ? request.getSubTopicCount() : 5;
            int maxRounds = request.getMaxIterations() != null ? request.getMaxIterations() : defaultMaxIterations;
            if (maxRounds < 1) {
                maxRounds = 1;
            }

            // --- Step 1: Break down topic into sub-topics, each with planned search queries ---
            PlanResult plan = planBreakdown(handle, sessionId, request.getTopic(), subTopicCount);

            // Save BREAKDOWN step and persist immediately so REST API returns real-time progress.
            // NOTE: always adopt the merged instance returned by save(). Step ids are DB-generated, so our
            // local child objects keep id=null after a merge; re-merging that stale graph later would make
            // Hibernate re-insert already-persisted steps (duplicate key on uq_session_order).
            ResearchStep breakdownStep = saveStep(session, 0, StepType.BREAKDOWN, "COMPLETED", plan.record());
            session.addStep(breakdownStep);
            streamService.sendProgress(sessionId, "Topic broken down into " + plan.topics().size() + " sub-topics");
            session = persistChecked(handle, session); // Persist BREAKDOWN step (cancellation checkpoint inside)

            log.info("Breakdown complete: {} sub-topics found for session {}", plan.topics().size(), sessionId);

            // --- Step 2: Process each sub-topic with iterative research rounds (retries + partial-failure tolerance) ---
            List<String> findingsBlocks = new ArrayList<>();
            List<String> failedSubTopics = new ArrayList<>();
            LinkedHashMap<String, String> aggregatedSources = new LinkedHashMap<>(); // normalized URL -> "Title — URL" line

            for (int i = 0; i < plan.topics().size(); i++) {
                SubTopic subTopic = plan.topics().get(i);
                try {
                    handle.ensureActive(); // checkpoint before each sub-topic round batch
                    ResearchRoundResult roundResult = researchOneSubTopic(handle, sessionId, subTopic, maxRounds);
                    findingsBlocks.add(buildFindingBlock(i + 1, subTopic.getTitle(), "COMPLETED", roundResult.note()));
                    aggregatedSources.putAll(roundResult.sourcesByUrl());

                    ResearchStep subtopicStep = saveStep(session, i + 1, StepType.SUBTOPIC, "COMPLETED", roundResult.note());
                    session.addStep(subtopicStep);
                    streamService.sendProgress(sessionId, "Completed research on: " + subTopic.getTitle());
                } catch (ResearchCancelledException e) {
                    throw e; // user cancellation is not a sub-topic failure — stop the whole run
                } catch (Exception e) {
                    log.error("Sub-topic research failed for '{}' on session {} after retries: {}",
                            subTopic.getTitle(), sessionId, e.getMessage(), e);
                    ResearchStep failedStep = saveStep(session, i + 1, StepType.SUBTOPIC, "FAILED", null);
                    session.addStep(failedStep);
                    streamService.sendProgress(sessionId, "Research on '" + subTopic.getTitle() + "' failed after retries");
                    failedSubTopics.add(subTopic.getTitle());
                }
                session = persistChecked(handle, session); // Persist each SUBTOPIC step incrementally (adopt merged instance — see note above)
            }

            if (findingsBlocks.isEmpty()) {
                String reason = "All sub-topic research attempts failed: " + String.join("; ", failedSubTopics);
                log.error("Session {}: {}", sessionId, reason);
                // Total failure — nothing to synthesize. Guarded: a cancelled/terminal run is not overwritten with FAILED.
                if (ownsSessionState(sessionId)) {
                    session.fail(reason);
                    try {
                        session = sessionRepo.save(session); // persist FAILED status (was silently lost before)
                    } catch (Exception persistError) {
                        log.error("Failed to persist total-failure state for session {}", sessionId, persistError);
                    }
                } else {
                    log.info("Skipping FAILED persistence for cancelled/terminal session {}", sessionId);
                }
                streamService.sendError(sessionId, reason);
                return;
            }

            // --- Step 3: Generate final report (streamed) with References built from captured sources ---
            handle.ensureActive(); // checkpoint before starting the long-running report stream
            generateFinalReport(handle, sessionId, session, request.getTopic(), plan.topics().size(),
                    findingsBlocks, failedSubTopics, aggregatedSources.values());
            // From here on the STREAM owns registry cleanup (its terminal callbacks unregister).
            reportStreamStarted = true;

        } catch (ResearchCancelledException e) {
            log.info("Pipeline for session {} stopped due to user cancellation", sessionId);
            // cancelResearch already persisted CANCELLED atomically. Re-assert in case an incremental save committed after
            // the flag was set and reverted the status field back to PROCESSING (rare race).
            reAssertCancellation(sessionId);
        } catch (Exception e) {
            log.error("Error during research orchestration for session {}", sessionId, e);
            if (session != null && ownsSessionState(sessionId)) {
                try {
                    session.fail(e.getMessage());
                    // Next free order index — the BREAKDOWN step at index 0 is already persisted in most failure paths
                    session.addStep(saveStep(session, session.getSteps().size(), StepType.BREAKDOWN, "FAILED", e.getMessage()));
                    sessionRepo.save(session); // Cascade saves steps too (no further saves after this point)
                } catch (Exception persistError) {
                    log.error("Failed to persist failure state for session {}", sessionId, persistError);
                }
            } else if (session != null) {
                log.info("Skipping FAILED persistence for cancelled/terminal session {}", sessionId);
            }
            streamService.sendError(sessionId, "Research failed: " + e.getMessage());
        } finally {
            // Release the registry entry unless the report stream has taken ownership of it.
            if (!reportStreamStarted) {
                cancellationRegistry.unregister(sessionId, handle);
            }
        }
    }

    // ==================== STEP 1: BREAKDOWN PLANNING ====================

    private PlanResult planBreakdown(ResearchCancellationRegistry.CancellationHandle handle, UUID sessionId, String topic, int count) {
        String system = """
                You are an expert research planner for a web-research agent.

                Decompose the given topic into exactly %d distinct, non-overlapping sub-topics that together provide comprehensive coverage of the subject.

                Requirements:
                - Produce EXACTLY %d sub-topics; no redundancy or overlapping scopes.
                - Titles must be concise (3–10 words); descriptions explain what to investigate (1–3 sentences).
                - Cover foundational concepts and advanced/practical aspects when appropriate; prioritise the most important dimensions for broad topics, split narrow topics into logical investigative components.
                - For each sub-topic provide 2–4 CONCRETE web search queries that a search engine would return good pages for: specific keyword phrases (not full questions), varying in focus so they surface different sources together.

                Output rules:
                - Return ONLY a valid JSON array — no markdown code fences, no explanations, no extra text.
                - Each element matches the schema: {"id": number, "title": string, "description": string, "searchQueries": [string]}
                """.formatted(count, count);

        String response = llmCallWithRetry(handle, system, "Research Topic: \"" + topic + "\"", TEMP_PLANNING);
        List<SubTopic> topics = parseSubTopics(response);

        if (topics.isEmpty()) {
            // Semantic retry: the model produced text that is not valid JSON — tell it exactly what was wrong.
            log.warn("Breakdown response for session {} was not valid JSON — retrying with a corrective prompt. Raw: {}",
                    sessionId, truncateForLog(response));
            String correctiveUser = ("Your previous response could not be parsed as the required JSON array.\n"
                    + "Previous output:\n" + truncateForLog(response)
                    + "\n\nRespond AGAIN with ONLY the JSON array — no markdown fences, no explanations.");
            response = llmCallWithRetry(handle, system, correctiveUser, TEMP_PLANNING);
            topics = parseSubTopics(response);
        }

        if (!topics.isEmpty()) {
            return new PlanResult(topics, normalizePlanJson(topic, topics));
        }

        // Last resort: a single broad research pass over the whole topic.
        log.error("Breakdown unparseable after retry for session {} — falling back to a single generic sub-topic", sessionId);
        String raw = response != null ? response : "";
        return new PlanResult(List.of(new SubTopic(1, topic, raw)), raw);
    }

    private List<SubTopic> parseSubTopics(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return List.of();
        }
        String jsonContent = extractJsonFromMarkdown(rawResponse);
        try {
            List<SubTopic> parsed = objectMapper.readValue(jsonContent, new TypeReference<List<SubTopic>>() {
            });
            List<SubTopic> valid = parsed.stream()
                    .filter(st -> st.getTitle() != null && !st.getTitle().isBlank())
                    .toList();
            for (int i = 0; i < valid.size(); i++) {
                SubTopic st = valid.get(i);
                if (st.getId() == 0) {
                    st.setId(i + 1);
                }
                if (st.getDescription() == null || st.getDescription().isBlank()) {
                    st.setDescription("");
                }
            }
            return new ArrayList<>(valid);
        } catch (Exception e) {
            log.error("Failed to parse sub-topic breakdown: {}", jsonContent, e);
            return List.of();
        }
    }

    private String normalizePlanJson(String topic, List<SubTopic> topics) {
        try {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("topic", topic);
            plan.put("subTopics", topics);
            return objectMapper.writeValueAsString(plan);
        } catch (Exception e) {
            return topics.stream().map(SubTopic::getTitle).collect(java.util.stream.Collectors.joining(", "));
        }
    }

    // ==================== STEP 2: ITERATIVE SUB-TOPIC RESEARCH ====================

    private ResearchRoundResult researchOneSubTopic(ResearchCancellationRegistry.CancellationHandle handle,
                                                   UUID sessionId, SubTopic subTopic, int maxRounds) {
        StringBuilder note = new StringBuilder();
        LinkedHashMap<String, String> sourcesByUrl = new LinkedHashMap<>(); // normalized URL -> "Title — URL" line

        for (int round = 1; round <= maxRounds; round++) {
            handle.ensureActive(); // checkpoint before each research round
            streamService.sendProgress(sessionId,
                    maxRounds > 1
                            ? "Researching: " + subTopic.getTitle() + " (round " + round + "/" + maxRounds + ")"
                            : "Researching: " + subTopic.getTitle());

            String system = round == 1 ? RESEARCH_ROUND_PROMPT : RESEARCH_FOLLOWUP_PROMPT;
            String user = buildRoundUserMessage(subTopic, note.toString(), sourcesByUrl);

            int urlsBefore = sourcesByUrl.size();
            String response = llmCallWithRetry(handle, system, user, TEMP_RESEARCH); // throws after final attempt (or on cancel)

            if (note.length() > 0) {
                note.append("\n\n").append(response.trim());
            } else {
                note.append(response.trim());
            }
            collectSourceInfo(response, sourcesByUrl);
            boolean newEvidence = sourcesByUrl.size() > urlsBefore;

            // Early stop: coverage looks sufficient (distinct URLs + substantive notes).
            if (isCovered(note.toString(), sourcesByUrl.size())) {
                break;
            }
            // A round after the first that produced no new evidence is very unlikely to help — stop.
            if (!newEvidence && round >= 2) {
                log.info("No new sources captured in round {} for sub-topic '{}'; stopping early", round, subTopic.getTitle());
                break;
            }
        }

        if (sourcesByUrl.isEmpty()) {
            // Research ran without capturing any page URLs — likely parametric knowledge only.
            log.warn("Sub-topic '{}' produced no source URLs during research; findings may be unverified", subTopic.getTitle());
        }

        return new ResearchRoundResult(note.toString(), sourcesByUrl);
    }

    private boolean isCovered(String note, int distinctSourceCount) {
        return note.length() >= MIN_NOTE_LENGTH_FOR_COVERAGE && distinctSourceCount >= MIN_SOURCES_FOR_COVERAGE;
    }

    private String buildRoundUserMessage(SubTopic subTopic, String currentNote, LinkedHashMap<String, String> sourcesByUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("Sub-topic: ").append(subTopic.getTitle()).append("\n");
        if (subTopic.getDescription() != null && !subTopic.getDescription().isBlank()) {
            sb.append("Description: ").append(subTopic.getDescription()).append("\n");
        }

        if (currentNote.isEmpty()) {
            sb.append("\nPlanned search queries — run ALL of these, plus useful variants:\n");
            List<String> queries = subTopic.getSearchQueries() != null && !subTopic.getSearchQueries().isEmpty()
                    ? subTopic.getSearchQueries()
                    : List.of(subTopic.getTitle());
            for (String q : queries) {
                sb.append("- ").append(q).append("\n");
            }
        } else {
            sb.append("\nResearch notes so far:\n").append(currentNote).append("\n\nSources already consulted: \n");
            if (sourcesByUrl.isEmpty()) {
                sb.append("none captured yet — make sure to list every page you actually read under '## Sources Consulted'.\n");
            } else {
                for (String line : sourcesByUrl.values()) {
                    sb.append("- ").append(line).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /** A "- Title — https://..." bullet line; group(1) is the URL. */
    private static final Pattern SOURCE_BULLET_PATTERN =
            Pattern.compile("(?m)^[-*][ \\t]+.*?(https?://[^\\s)+\\]]+)");
    /** Any bare URL occurrence (display-preserving). */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s),;`'+]+");

    /**
     * Extracts "Title — URL" bullets and bare URLs from an LLM research response, accumulating them into the
     * provided map (deduplicated by normalized URL). The value line is used verbatim in report References.
     */
    private void collectSourceInfo(String response, LinkedHashMap<String, String> sourcesByUrl) {
        if (response == null || response.isBlank()) {
            return;
        }

        Matcher bullet = SOURCE_BULLET_PATTERN.matcher(response);
        while (bullet.find()) {
            addSourceLine(bullet.group().trim(), sourcesByUrl);
        }

        // Bare URLs not captured in a bullet still count toward coverage and become References entries.
        Map<String, String> originalByUrl = new HashMap<>(); // normalized -> first-seen original casing
        Matcher urlMatcher = URL_PATTERN.matcher(response);
        while (urlMatcher.find()) {
            originalByUrl.putIfAbsent(normalizeUrl(urlMatcher.group()), urlMatcher.group());
        }
        for (Map.Entry<String, String> e : originalByUrl.entrySet()) {
            sourcesByUrl.putIfAbsent(e.getKey(), "— " + e.getValue()); // bullets above win when present
        }
    }

    private void addSourceLine(String line, LinkedHashMap<String, String> targets) {
        Matcher urlMatcher = URL_PATTERN.matcher(line);
        if (urlMatcher.find()) {
            targets.putIfAbsent(normalizeUrl(urlMatcher.group()), line);
            return;
        }
        // Bullet without a recognizable URL — key by the line itself so duplicate bullets collapse.
        targets.putIfAbsent(line.toLowerCase(), line);
    }

    private String normalizeUrl(String url) {
        return url.replaceAll("[.;,]+$", "").toLowerCase();
    }

    private String buildFindingBlock(int index, String title, String status, String note) {
        return "### " + index + ". " + title + " [" + status + "]\n\n" + (note == null ? "" : note);
    }

    // ==================== STEP 3: FINAL REPORT STREAMING ====================

    private void generateFinalReport(ResearchCancellationRegistry.CancellationHandle handle, UUID sessionId, ResearchSession session, String topic, int totalSubTopics,
                                     List<String> findingsBlocks, List<String> failedSubTopics, Iterable<String> aggregatedSources) {
        StringBuilder input = new StringBuilder();
        input.append("Research Topic: ").append(topic).append("\n\n");

        input.append("## Sub-Topic Notes\n\n");
        for (String block : findingsBlocks) {
            input.append(block).append("\n\n");
        }

        if (!failedSubTopics.isEmpty()) {
            input.append("## Failed Sub-topics (no findings — mention these as research gaps in the report)\n");
            for (String t : failedSubTopics) {
                input.append("- ").append(t).append("\n");
            }
            input.append("\n");
        }

        input.append("## Aggregated Source List (use ONLY these entries for the References section)\n");
        int n = 1;
        boolean any = false;
        for (String s : aggregatedSources) {
            input.append(n++).append(". ").append(s).append("\n");
            any = true;
        }
        if (!any) {
            input.append("(no page URLs were captured during research — rely on search snippets and note limited evidence depth under Research Gaps & Confidence)");
        }

        log.info("Starting final report generation for session {}", sessionId);
        streamService.sendReportStart(sessionId);

        // Use AtomicReference to capture the full report content during streaming
        java.util.concurrent.atomic.AtomicReference<StringBuilder> reportBuffer = new java.util.concurrent.atomic.AtomicReference<>(new StringBuilder());

        Flux<String> reportStream = llmGateway.streamComplete(SYNTHESIS_PROMPT, input.toString(), TEMP_SYNTHESIS);

        // Stream the final report back to the frontend and accumulate content. Terminal callbacks run later
        // (on a Reactor thread) and are guarded by ownsSessionState(): if the user cancelled in between, we must
        // not overwrite CANCELLED with COMPLETED/FAILED. Registry cleanup happens on every terminal path —
        // doOnCancel covers the dispose path triggered by cancelResearch().
        Disposable subscription = reportStream
                .doOnNext(chunk -> {
                    streamService.sendReportChunk(sessionId, chunk);
                    reportBuffer.getAndUpdate(sb -> sb.append(chunk));  // Accumulate for saving after streaming completes
                })
                .doOnCancel(() -> cancellationRegistry.unregister(sessionId, handle))
                .subscribe(
                        null,  // onNext already handled above via doOnNext
                        error -> {
                            try {
                                log.error("Error during report streaming", error);
                                if (ownsSessionState(sessionId)) {
                                    session.fail("Failed to generate final report: " + error.getMessage());
                                    streamService.sendError(sessionId, "Failed to generate final report: " + error.getMessage());
                                } else {
                                    log.info("Skipping FAILED persistence for cancelled/terminal session {}", sessionId);
                                }
                            } finally {
                                cancellationRegistry.unregister(sessionId, handle);
                            }
                        },
                        () -> {
                            // Report generation complete - update session status with full content.
                            // This callback runs on a Reactor thread (no ambient Hibernate session): the steps
                            // bag was fetch-joined up front and repo.save() opens its own short transaction.
                            try {
                                if (ownsSessionState(sessionId)) {
                                    String fullReport = reportBuffer.get().toString();
                                    session.setFinalReport(fullReport);
                                    session.complete();
                                    session.addStep(saveStep(session, totalSubTopics + 1, StepType.FINAL_REPORT, "COMPLETED", fullReport));
                                    sessionRepo.save(session); // Cascade saves steps too

                                    // Send final REPORT_DONE event with the complete report
                                    streamService.sendReportDone(sessionId, fullReport);
                                    streamService.sendProgress(sessionId, "Research complete!");
                                } else {
                                    log.info("Skipping COMPLETED write for session {} — run was cancelled or is already terminal", sessionId);
                                }
                            } catch (Exception callbackError) {
                                log.error("Failed to finalize session {} after streaming", sessionId, callbackError);
                                try {
                                    if (ownsSessionState(sessionId)) {
                                        session.fail("Failed to persist final report: " + callbackError.getMessage());
                                        sessionRepo.save(session);
                                    }
                                } catch (Exception ignore) {
                                    // nothing else we can do on this thread
                                }
                                streamService.sendError(sessionId, "Research failed to finalize: " + callbackError.getMessage());
                            } finally {
                                cancellationRegistry.unregister(sessionId, handle);
                            }
                        }
                );

        // Expose the subscription to the registry so cancelResearch() can dispose it mid-stream.
        handle.setActiveStream(subscription);
    }

    // ==================== LLM RETRIES & HELPERS ====================

    /**
     * Run an LLM completion with bounded retries: 1 initial attempt + 2 retries with growing backoff.
     * Throws IllegalStateException once all attempts are exhausted so callers can decide how to degrade.
     */
    private String llmCallWithRetry(ResearchCancellationRegistry.CancellationHandle handle, String systemPrompt, String userMessage, Double temperature) {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_LLM_ATTEMPTS; attempt++) {
            handle.ensureActive(); // checkpoint — cancelled runs start no further LLM attempts
            try {
                return llmGateway.complete(systemPrompt, userMessage, temperature);
            } catch (RuntimeException e) {
                last = e;
                log.warn("LLM call attempt {}/{} failed: {}", attempt, MAX_LLM_ATTEMPTS, e.getMessage());
                if (attempt < MAX_LLM_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_BACKOFF_MILLIS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while retrying LLM call", ie);
                    }
                }
            }
        }
        throw new IllegalStateException("LLM call failed after " + MAX_LLM_ATTEMPTS + " attempts", last);
    }

    private String truncateForLog(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 2000 ? s.substring(0, 2000) + "…" : s;
    }

    /** Breakdown stage result: planned sub-topics plus a record string for the BREAKDOWN step. */
    private record PlanResult(List<SubTopic> topics, String record) {
    }

    /** One sub-topic's research output: accumulated note text and captured sources (normalized URL -> display line). */
    private record ResearchRoundResult(String note, LinkedHashMap<String, String> sourcesByUrl) {
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
     *
     * <p>Coordinated three-part action:</p>
     * <ol>
     *   <li>Signal the in-flight pipeline — cooperative checkpoints stop any further sub-topic rounds / LLM calls,
     *       and the active report-stream subscription (if streaming) is disposed, cancelling its upstream request.</li>
     *   <li>Persist CANCELLED immediately with an atomic conditional UPDATE that only applies while the row is still
     *       PROCESSING, so a run that has just completed/failed is never clobbered.</li>
     *   <li>Emit the progress SSE event so connected clients see the cancelled state (also picked up by polling).</li>
     * </ol>
     */
    public void cancelResearch(UUID sessionId) {
        ResearchSession session = sessionRepo.findById(sessionId).orElse(null);
        if (session == null || !"PROCESSING".equals(session.getStatus().name())) {
            return; // not running — the controller enforces 400 for these cases anyway
        }

        boolean signalled = cancellationRegistry.cancel(sessionId);   // flag + dispose active stream
        int updated = sessionRepo.markCancelledIfProcessing(sessionId, LocalDateTime.now(), "Cancelled by user");

        if (!signalled) {
            log.warn("Cancel requested for {} but no in-flight pipeline handle found — status persisted only", sessionId);
        } else if (updated == 0) {
            log.info("Cancel lost the terminal-state race for session {}; DB state left as-is", sessionId);
        }

        streamService.sendProgress(sessionId, "Research cancelled by user");
    }

    /**
     * Incremental persist during a run — checks the cancellation flag first so a cancelled run never writes its stale
     * PROCESSING row over CANCELLED (an in-flight save would otherwise revert the status field via merge semantics).
     */
    private ResearchSession persistChecked(ResearchCancellationRegistry.CancellationHandle handle, ResearchSession session) {
        handle.ensureActive(); // throws ResearchCancelledException if a user cancelled
        return sessionRepo.save(session);
    }

    /**
     * True only while this run still owns the session's terminal state: not cancelled by the user AND the DB row is
     * still PROCESSING. Used to guard every late (pipeline or stream-thread) write of COMPLETED/FAILED.
     */
    private boolean ownsSessionState(UUID sessionId) {
        if (cancellationRegistry.isCancelled(sessionId)) {
            return false;
        }
        ResearchSession current = sessionRepo.findById(sessionId).orElse(null);
        return current != null && "PROCESSING".equals(current.getStatus().name());
    }

    /**
     * Re-assert CANCELLED after the pipeline stopped on a cancel signal — repairs the rare race where an incremental save
     * committed AFTER the flag was set and reverted the status to PROCESSING. The conditional UPDATE can never touch a run
     * that has genuinely reached a terminal state.
     */
    private void reAssertCancellation(UUID sessionId) {
        try {
            int updated = sessionRepo.markCancelledIfProcessing(sessionId, LocalDateTime.now(), "Cancelled by user");
            if (updated == 0) {
                log.debug("Re-assert cancel for {} was a no-op — status already terminal", sessionId);
            }
        } catch (Exception e) {
            log.error("Failed to re-assert cancellation state for session {}", sessionId, e);
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
        Page<ResearchSession> allByOrderByCreatedAtDesc = sessionRepo.findAllByOrderByCreatedAtDesc(pageable);
        return allByOrderByCreatedAtDesc;
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
            return llmGateway.complete(prompt, "", TEMP_PLANNING);
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

        Pattern pattern = Pattern.compile(
                "```(?:\\w+)?\\s*(.*?)\\s*```",
                Pattern.DOTALL);

        Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            String candidate = matcher.group(1).trim();

            if (!candidate.isEmpty() &&
                    (candidate.startsWith("{") || candidate.startsWith("["))) {
                return candidate;
            }
        }

        // Fallback
        String trimmed = content.trim();
        trimmed = trimmed.replaceFirst("^```\\w*\\s*", "");
        trimmed = trimmed.replaceFirst("\\s*```$", "");

        return trimmed.trim();
    }

    /**
     * Delete a research session and its associated steps (cascade delete).
     * Only works on non-running sessions (COMPLETED, FAILED, CANCELLED).
     *
     * @return the deleted session, or null if not found/not deletable
     */
    public ResearchSession deleteSession(UUID sessionId) {
        ResearchSession session = getResearch(sessionId);
        if (session == null) {
            return null; // Will be handled by controller as 404
        }
        if ("PROCESSING".equals(session.getStatus().name())) {
            throw new IllegalStateException("Cannot delete a processing research session. Use the cancel endpoint instead.");
        }
        sessionRepo.deleteById(sessionId);
        log.info("Deleted research session: {}", sessionId);
        return session;
    }

    /**
     * Delete multiple research sessions in bulk. All-or-nothing rollback semantics — if any deletion fails, none are deleted.
     * Only works on non-running sessions (COMPLETED, FAILED, CANCELLED).
     *
     * @return list of successfully deleted sessions (always the full list if this method succeeds)
     */
    @Transactional
    public List<ResearchSession> deleteSessionsInBulk(List<UUID> sessionIds) {
        // Validate all sessions first — if any fail validation, reject entire batch (rollback behavior)
        for (UUID id : sessionIds) {
            ResearchSession session = getResearch(id);
            if (session == null || "PROCESSING".equals(session.getStatus().name())) {
                throw new IllegalStateException("Cannot delete session: " + id + (session == null ? " (not found)" : " — is still processing"));
            }
        }

        // All validations passed — proceed with batch deletion
        List<ResearchSession> sessions = new java.util.ArrayList<>();
        for (UUID id : sessionIds) {
            ResearchSession session = getResearch(id);
            if (session != null && !"PROCESSING".equals(session.getStatus().name())) {
                sessions.add(session);
            }
        }

        List<UUID> idsToDelete = sessions.stream().map(ResearchSession::getId).toList();
        if (!idsToDelete.isEmpty()) {
            for (UUID id : idsToDelete) {
                sessionRepo.deleteById(id); // Individual delete within @Transactional — rollback on any failure
            }
            log.info("Deleted {} research sessions in bulk: {}", idsToDelete.size(), idsToDelete);
        }

        return sessions;
    }

    /**
     * Inner class for sub-topic representation (planned by the breakdown stage, enriched with search queries).
     */
    private static class SubTopic {
        private int id;
        private String title;
        private String description;
        private List<String> searchQueries;

        public SubTopic() {
        }

        public SubTopic(int id, String title, String description) {
            this.id = id;
            this.title = title;
            this.description = description;
        }

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<String> getSearchQueries() {
            return searchQueries;
        }

        public void setSearchQueries(List<String> searchQueries) {
            this.searchQueries = searchQueries;
        }
    }
}
