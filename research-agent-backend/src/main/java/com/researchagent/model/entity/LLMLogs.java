package com.researchagent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One document per LLM request/response pair captured by {@code LoggingAdvisor} (collection {@code llm_logs}).
 *
 * <p>Because the advisor sits inside Spring AI's ToolCallingAdvisor, a single research round that makes
 * several tool-calling model rounds produces one document per underlying model request. All documents of one
 * research session share the same {@link #sessionId}, so per-session LLM history is queryable via
 * {@code LlmLogRepository.findBySessionIdOrderByCreatedAtAsc}.</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "llm_logs")
public class LLMLogs {

    @Id
    private String id;

    /** Research session that triggered this call — null for calls made outside a session (e.g. startup checks). */
    @Indexed
    private UUID sessionId;

    /** Model name from the request options, when available. */
    private String model;

    /** Full prompt sent to the model (all messages rendered as text). */
    @ToString.Exclude
    private String prompt;

    /** Full response text — for streamed calls this is every chunk concatenated in arrival order. */
    @ToString.Exclude
    private String response;

    /** True when the completion was streamed (final report generation), false for single-shot calls. */
    private boolean streaming;

    /** "SUCCESS" or "ERROR". */
    private String status = "SUCCESS";

    /** Exception message when {@link #status} is ERROR. */
    @ToString.Exclude
    private String errorMessage;

    @Indexed
    private LocalDateTime createdAt;

    /** Wall-clock duration of the call (stream: from subscription to completion). */
    private long durationMs;
}
