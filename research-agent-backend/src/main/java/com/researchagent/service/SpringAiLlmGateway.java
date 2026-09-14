package com.researchagent.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Spring AI implementation of {@link LlmGateway}.
 *
 * <p>All calls share one consistent fluent path so the ChatClient configuration
 * (model, base-url and default tools registered via {@code builder.defaultTools})
 * always applies. Per-request options only carry a temperature when requested —
 * Spring AI merges them over the builder defaults ({@link org.springframework.ai.model.ModelOptionsUtils}),
 * so passing a temperature-only {@link DefaultChatOptions} keeps the configured model.</p>
 */
@Component
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatClient chatClient;

    public SpringAiLlmGateway(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String complete(String systemPrompt, String userMessage, Double temperature) {
        if (temperature == null) {
            return chatClient.prompt()
                    .system(systemPrompt)
                    .user(userMessage)
                    .call()
                    .content();
        }
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .options(temperatureOptions(temperature))
                .call()
                .content();
    }

    @Override
    public Flux<String> streamComplete(String systemPrompt, String userMessage, Double temperature) {
        if (temperature == null) {
            return chatClient.prompt()
                    .system(systemPrompt)
                    .user(userMessage)
                    .stream()
                    .content();
        }
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .options(temperatureOptions(temperature))
                .stream()
                .content();
    }

    private DefaultChatOptions temperatureOptions(Double temperature) {
        var options = new DefaultChatOptions();
        options.setTemperature(temperature);
        return options;
    }
}
