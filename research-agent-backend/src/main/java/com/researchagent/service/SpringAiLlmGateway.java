package com.researchagent.service;

import com.researchagent.advisor.LoggingAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.UUID;

/**
 * Spring AI implementation of {@link LlmGateway}.
 *
 * <p>All calls share one consistent fluent path so the ChatClient configuration
 * (model, base-url and default tools registered via {@code builder.defaultTools})
 * always applies. Per-request options only carry a temperature when requested —
 * Spring AI merges them over the builder defaults ({@link org.springframework.ai.model.ModelOptionsUtils}),
 * so passing a temperature-only {@link OpenAiChatOptions} keeps the configured model.</p>
 *
 * <p>Structured completions use Spring AI 2.0's {@code call().entity(...)}: a JSON schema is
 * generated from the target type and appended to the prompt, and the final response (after any
 * tool-call rounds) is parsed into it. The prompt-based approach is used deliberately — NOT
 * {@code useProviderStructuredOutput()} — because the backend is a local LLM served through an
 * OpenAI-compatible endpoint where native structured-output support is unreliable.</p>
 *
 * <p>The research session id (when present) is attached to the per-request advisor context under
 * {@link LoggingAdvisor#SESSION_ID_CONTEXT_KEY}, so every LLM call of a session lands in the
 * {@code llm_logs} collection tagged with that session. The value travels with the request object —
 * no ThreadLocal — which keeps attribution correct even when stream callbacks run on Reactor threads.</p>
 */
@Component
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatClient chatClient;

    public SpringAiLlmGateway(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String complete(UUID sessionId, String systemPrompt, String userMessage, Double temperature) {
        return prompt(sessionId, systemPrompt, userMessage, temperature)
                .call()
                .content();
    }

    @Override
    public Flux<String> streamComplete(UUID sessionId, String systemPrompt, String userMessage, Double temperature) {
        return prompt(sessionId, systemPrompt, userMessage, temperature)
                .stream()
                .content();
    }

    @Override
    public <T> T completeStructured(UUID sessionId, String systemPrompt, String userMessage, Double temperature, Class<T> type) {
        return prompt(sessionId, systemPrompt, userMessage, temperature)
                .call()
                .entity(type);
    }

    @Override
    public <T> T completeStructured(UUID sessionId, String systemPrompt, String userMessage, Double temperature, ParameterizedTypeReference<T> type) {
        return prompt(sessionId, systemPrompt, userMessage, temperature)
                .call()
                .entity(type);
    }

    /**
     * Build the shared fluent request. Per-request options only carry a temperature when requested —
     * Spring AI merges them over the builder defaults, so the configured model always applies.
     */
    private ChatClient.ChatClientRequestSpec prompt(UUID sessionId, String systemPrompt, String userMessage, Double temperature) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage);
        if (sessionId != null) {
            // Attach the session id to the advisor context so LoggingAdvisor can tag this call's log entry.
            spec.advisors(a -> a.param(LoggingAdvisor.SESSION_ID_CONTEXT_KEY, sessionId));
        }
        if (temperature != null) {
            spec.options(temperatureOptions(temperature));
        }
        return spec;
    }

    private OpenAiChatOptions.Builder temperatureOptions(Double temperature) {
       return OpenAiChatOptions.builder()
               .timeout(Duration.ofSeconds(1200))
                .temperature(temperature);
    }
}
