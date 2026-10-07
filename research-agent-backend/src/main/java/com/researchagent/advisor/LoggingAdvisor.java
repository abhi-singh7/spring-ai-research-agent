package com.researchagent.advisor;

import com.researchagent.model.entity.LLMLogs;
import com.researchagent.repository.LlmLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Advisor that records every LLM request/response pair to MongoDB (collection {@code llm_logs}) and emits a
 * one-line summary per call on the application log.
 *
 * <p>Implements BOTH {@link CallAdvisor} and {@link StreamAdvisor}: Spring AI routes {@code .call()} through
 * the call-advisor chain and {@code .stream()} through the stream-advisor chain, so an advisor that implements
 * only one of them silently misses the other mode. The final report generation is streamed — without the
 * stream side the biggest LLM call of a research session would never be logged.</p>
 *
 * <p>Ordering: with {@link #getOrder()} = 0 this advisor sits INSIDE the ToolCallingAdvisor (whose default
 * order is {@code HIGHEST_PRECEDENCE + 300}) and OUTSIDE the terminal ChatModel advisors. Each underlying model
 * round of a multi-turn tool-calling exchange therefore produces its own log document — one per actual LLM
 * request, including intermediate rounds that only contain tool calls.</p>
 *
 * <p>Per-session attribution: callers pass the research session id through the advisor context
 * ({@code spec.advisors(a -> a.param(SESSION_ID_CONTEXT_KEY, sessionId))} — see {@code SpringAiLlmGateway}).
 * The value travels with the request object, so it is available even when stream callbacks run on Reactor
 * threads. Calls without a session id (e.g. the startup MCP verification) are logged with {@code null}.</p>
 */
public class LoggingAdvisor implements CallAdvisor, StreamAdvisor {

    /** Advisor-context key carrying the research session id for this request. */
    public static final String SESSION_ID_CONTEXT_KEY = "researchSessionId";

    private static final Logger logger = LoggerFactory.getLogger(LoggingAdvisor.class);

    private final LlmLogRepository logRepository;

    public LoggingAdvisor(LlmLogRepository logRepository) {
        this.logRepository = logRepository;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    /** See class javadoc — 0 keeps this advisor inside the ToolCallingAdvisor. */
    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long startedAt = System.currentTimeMillis();
        try {
            ChatClientResponse response = chain.nextCall(request);
            persistLog(request, renderResponse(response), false, null, startedAt);
            return response;
        } catch (RuntimeException e) {
            persistLog(request, null, false, e, startedAt);
            throw e;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        long startedAt = System.currentTimeMillis();
        StringBuilder responseBuffer = new StringBuilder();
        return chain.nextStream(request)
                .doOnNext(chunk -> {
                    String text = renderResponse(chunk);
                    if (!text.isEmpty()) {
                        responseBuffer.append(text);
                    }
                })
                .doOnError(e -> persistLog(request, responseBuffer.toString(), true, e, startedAt))
                .doOnComplete(() -> persistLog(request, responseBuffer.toString(), true, null, startedAt));
    }

    /**
     * Renders one (possibly partial) response: the text content plus any tool calls. Tool-call-only
     * rounds render as {@code [tool_call name(args)]} markers instead of an empty string, so the
     * intermediate rounds of a tool-calling exchange stay auditable in the log.
     */
    private static String renderResponse(ChatClientResponse response) {
        Generation generation = Optional.ofNullable(response)
                .map(ChatClientResponse::chatResponse)
                .map(ChatResponse::getResult)
                .orElse(null);
        if (generation == null || generation.getOutput() == null) {
            return "";
        }
        AssistantMessage output = generation.getOutput();
        StringBuilder sb = new StringBuilder();
        String text = output.getText();
        if (text != null && !text.isBlank()) {
            sb.append(text);
        }
        for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("[tool_call ").append(toolCall.name())
                    .append("(").append(toolCall.arguments() == null ? "" : toolCall.arguments()).append(")]");
        }
        return sb.toString();
    }

    /**
     * Renders the full conversation sent to the model, one labelled block per message. Unlike
     * {@code Prompt.getContents()} — which only concatenates message texts — this also renders assistant
     * tool calls and tool results: those live outside the text field (in {@code AssistantMessage.getToolCalls()}
     * and {@code ToolResponseMessage.getResponses()}), so a plain getContents() makes every round of a
     * tool-calling exchange look byte-identical in the log.
     */
    private static String renderPrompt(ChatClientRequest request) {
        if (request.prompt() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message message : request.prompt().getInstructions()) {
            if (message instanceof ToolResponseMessage toolResponse) {
                for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                    sb.append("Tool[").append(response.name()).append("]: ")
                            .append(response.responseData() == null ? "" : response.responseData())
                            .append("\n\n");
                }
            } else if (message instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty()) {
                String text = assistant.getText();
                if (text != null && !text.isBlank()) {
                    sb.append("Assistant: ").append(text).append("\n");
                }
                for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                    sb.append("Assistant [tool_call ").append(toolCall.name())
                            .append("(").append(toolCall.arguments() == null ? "" : toolCall.arguments()).append(")]\n");
                }
                sb.append("\n");
            } else {
                sb.append(message.getMessageType().name().toLowerCase())
                        .append(": ")
                        .append(message.getText() == null ? "" : message.getText())
                        .append("\n\n");
            }
        }
        return sb.toString();
    }

    private static String modelOf(ChatClientRequest request) {
        return Optional.ofNullable(request.prompt())
                .map(p -> p.getOptions())
                .map(o -> o.getModel())
                .orElse(null);
    }

    /** Session id attached by {@code SpringAiLlmGateway}, or null for non-session calls. */
    private static UUID sessionIdOf(ChatClientRequest request) {
        Object value = request.context().get(SESSION_ID_CONTEXT_KEY);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid session id in advisor context: {}", s);
                return null;
            }
        }
        return null;
    }

    /**
     * Persist one log document. Failures are logged and swallowed — logging must never break the research
     * pipeline. Stream terminal callbacks run on Reactor threads with no ambient transaction, which is fine:
     * each save() is a short standalone Mongo operation (same pattern the orchestrator uses for late writes).
     */
    private void persistLog(ChatClientRequest request, String response, boolean streaming, Throwable error, long startedAt) {
        UUID sessionId = sessionIdOf(request);
        LLMLogs logDoc = LLMLogs.builder()
                .sessionId(sessionId)
                .model(modelOf(request))
                .prompt(renderPrompt(request))
                .response(response)
                .streaming(streaming)
                .status(error == null ? "SUCCESS" : "ERROR")
                .errorMessage(error == null ? null : String.valueOf(error.getMessage()))
                .createdAt(LocalDateTime.now())
                .durationMs(System.currentTimeMillis() - startedAt)
                .build();

        if (error != null) {
            logger.warn("LLM call failed [session={} streaming={} model={}]: {}",
                    sessionId, streaming, logDoc.getModel(), error.getMessage());
        } else {
            logger.info("LLM call completed [session={} streaming={} model={}] prompt={} chars, response={} chars, {} ms",
                    sessionId, streaming, logDoc.getModel(),
                    logDoc.getPrompt().length(),
                    response.length(),
                    logDoc.getDurationMs());
        }

        try {
            logRepository.save(logDoc);
        } catch (Exception e) {
            logger.warn("Failed to persist LLM log for session {}: {}", sessionId, e.getMessage());
        }
    }
}
