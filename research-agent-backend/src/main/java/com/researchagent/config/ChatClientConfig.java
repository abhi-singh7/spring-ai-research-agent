package com.researchagent.config;

import com.researchagent.tool.WebSearchTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder,
                          WebSearchTool webSearchTool) {

        return builder
                .defaultTools(webSearchTool)
                .build();
    }
}
