package com.researchagent.service;

import org.springframework.core.ParameterizedTypeReference;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Seam between the research orchestration logic and Spring AI's fluent ChatClient API.
 *
 * <p>The orchestrator only ever talks to this interface, which keeps the pipeline
 * unit-testable (mock this gateway — no deep-stubbing of the fluent builder chain)
 * and isolates it from framework-specific option/tool plumbing.</p>
 *
 * <p>Every method takes the research session id as its first parameter. The implementation attaches it to
 * the ChatClient advisor context so {@code LoggingAdvisor} can attribute each LLM call to its session in the
 * {@code llm_logs} collection. Pass {@code null} for calls made outside a research session.</p>
 */
public interface LlmGateway {

    /**
     * Run a single completion with tools auto-executed (multi-turn tool calls are handled
     * internally by Spring AI). A {@code null} temperature means "use the model defaults".
     */
    String complete(UUID sessionId, String systemPrompt, String userMessage, Double temperature);

    /**
     * Stream a completion token-by-token. Used for final report generation so SSE chunks
     * reach the frontend while the report is being written.
     */
    Flux<String> streamComplete(UUID sessionId, String systemPrompt, String userMessage, Double temperature);

    /**
     * Run a single completion and bind the final response to a typed object using Spring AI's
     * structured-output support ({@code call().entity(...)}): a JSON schema is generated from
     * {@code type}, appended to the prompt, and the model's answer is parsed into it.
     *
     * <p>Throws a {@link RuntimeException} when the answer cannot be converted — callers may retry.</p>
     */
    <T> T completeStructured(UUID sessionId, String systemPrompt, String userMessage, Double temperature, Class<T> type);

    /**
     * Same as {@link #completeStructured(UUID, String, String, Double, Class)} for generic types such as
     * {@code List<SubTopic>} (the schema is generated from the parameterized type).
     */
    <T> T completeStructured(UUID sessionId, String systemPrompt, String userMessage, Double temperature, ParameterizedTypeReference<T> type);
}
