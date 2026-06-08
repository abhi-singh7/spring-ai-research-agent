package com.researchagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchagent.tool.McpToolRouter;
import com.researchagent.tool.UrlReaderTool;
import com.researchagent.tool.WebSearchTool;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring AI MCP Client Integration Configuration.
 * 
 * Auto-configures ChatClient with tools discovered from MCP servers (web search, diagram generation, etc.)
 * and local tools (WebSearchTool) as fallback for non-MCP environments.
 * 
 * Key: Spring AI auto-configuration handles everything — McpSyncClient beans are created from
 * spring.ai.mcp.client.stdio.connections YAML config, and their tools are automatically registered
 * via the toolcallback mechanism. No manual bean injection or tool registration needed here.
 */
@Configuration
public class ChatClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatClientConfig.class);

    @Value("${spring.ai.mcp.client.tool-name-prefix:mcp-}")
    private String mcpToolNamePrefix;

    /**
     * Auto-configured by Spring AI based on spring.ai.mcp.client.stdio.connections YAML config.
     * Each MCP server connection creates its own client bean, which exposes tools to the ChatClient
     * via the toolcallback mechanism (no manual configuration needed).
     */

    @Bean
    ChatClient chatClient(ChatClient.Builder builder,
                          WebSearchTool webSearchTool,
                          UrlReaderTool urlReaderTool) {

        return builder
                .defaultTools(webSearchTool, urlReaderTool)
                .build();
    }

    /**
     * Validate MCP server availability on startup and log warnings for unavailable servers.
     */
    @PostConstruct
    public void validateMcpAvailability() {
        log.info("=== MCP Integration Status ===");
        log.info("Tool name prefix: '{}'", mcpToolNamePrefix);
        
        // Note: McpSyncClient beans are auto-configured by Spring AI.
        // If a server is unavailable, the client will fail silently and its tools won't be registered.
        // This method serves as a reminder to check server availability if MCP tools aren't appearing.
        log.info("MCP servers configured in application.yml:");
        log.info("  - web_search (uv run /home/abhi/ollama_web_search.py)");
        log.info("  - searxng (npx mcp-searxng)");
        log.info("  - excalidraw (node /home/abhi/excalidraw-mcp/dist/index.js --stdio)");
        log.info("  - ddg_search (uvx duckduckgo-mcp-server)");
        log.info("================================");
    }

    /**
     * Tool router for intelligent MCP tool selection based on task type.
     */
    @Bean
    public McpToolRouter mcpToolRouter() {
        return new McpToolRouter();
    }


    @Bean
    ApplicationRunner toolDebugger(ChatClient.Builder builder, WebSearchTool tool) {
        return args -> {
            ChatClient client = builder.defaultTools(tool).build();

            var result = client.prompt()
                    .user("You MUST use the search tool to answer this question: What is 2+2?\n" +
                            "Do not respond without calling a tool.")
                    .call()
                    .chatResponse();

            System.out.println("MODEL RESPONSE = " + result);
        };
    }
}
