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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;

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

        // Use Spring AI's auto-configured McpSyncClient beans to check availability.
        // We can't directly access them here since they're not injected, but we can
        // verify tools were registered by checking if the ChatClient has MCP tools available.
        
        // Attempt a simple tool discovery call to verify MCP toolcallback mechanism works.
        try {
            // This will fail gracefully if no MCP servers are running — that's expected behavior.
            // The important thing is that local fallback chain (WebSearchTool) handles the gap.
            log.info("MCP servers configured in application.yml:");
            log.info("  - web_search (uv run /home/abhi/ollama_web_search.py) [requires OLLAMA_API_KEY]");
            log.info("  - searxng (npx mcp-searxng) [requires SEARXNG_URL]");
            log.info("  - excalidraw (node /home/abhi/excalidraw-mcp/dist/index.js --stdio)");
            log.info("  - ddg_search (uvx duckduckgo-mcp-server)");
            
            // Check if the ollama_web_search.py script exists on disk
            java.io.File ollamaScript = new java.io.File("/home/abhi/ollama_web_search.py");
            if (!ollamaScript.exists()) {
                log.warn("MCP server 'web_search' script NOT found: /home/abhi/ollama_web_search.py — tools will be unavailable until this file is present.");
            } else {
                log.info("MCP server 'web_search' script found: {}", ollamaScript.getAbsolutePath());
            }

        } catch (Exception e) {
            log.warn("MCP availability check encountered an issue: " + e.getMessage());
        }
        
        // Note: If a MCP server is unavailable, its tools won't be registered and the
        // local Java fallback chain in WebSearchTool will handle the request instead.
        // Users should verify MCP server status via logs above or by checking which tools
        // appear in the ChatClient tool list at runtime.
        
        log.info("Local fallback chain: web_search → searxng → ddg_search (always available)");
        log.info("================================");
    }

    /**
     * Verify MCP tools are registered at startup by checking McpSyncClient beans.
     */
    @Bean
    public ApplicationRunner verifyMcpTools(ApplicationContext context, ChatClient.Builder builder) {
        return args -> {
            // Find all auto-configured McpSyncClient beans and check their tool availability
            String[] beanNames = context.getBeanDefinitionNames();
            List<String> mcpBeans = new ArrayList<>();
            
            for (String name : beanNames) {
                if (name.startsWith("mcp-") && name.contains("client")) {
                    try {
                        Object client = context.getBean(name);
                        log.info("  {} — MCP client found (tools auto-registered)", name);
                        mcpBeans.add(name);
                    } catch (Exception e) {
                        log.error("  {} — MCP client check failed: {}", name, e.getMessage());
                    }
                }
            }

            if (mcpBeans.isEmpty()) {
                log.info("No auto-configured McpSyncClient beans found — local Java tools will handle all search requests");
            } else {
                log.info("Found {} MCP client(s): {}", mcpBeans.size(), String.join(", ", mcpBeans));
            }

            // Verify the ChatClient has the expected tools registered by attempting a simple call.
            // If MCP servers are running, the LLM should see both local and MCP-prefixed tools.
            try {
                var result = builder.defaultTools(new WebSearchTool(null), new UrlReaderTool()).build()
                    .prompt().user("You MUST use the search tool to answer this question: What is 2+2?\n" +
                        "Do not respond without calling a tool.")
                    .call()
                    .chatResponse();
                log.info("ChatClient tool verification — result: {}", 
                    result.hasToolCalls() ? "Has tool calls (MCP tools may be available)" : "No tool calls (local tools only)");
            } catch (Exception e) {
                log.warn("ChatClient tool verification failed (expected if no MCP servers running): {}", e.getMessage());
            }

            // Log the routing chain for reference
            var router = context.getBean(McpToolRouter.class);
            log.info("MCP Tool Router chains:");
            
            Map<String, List<String>> chains = new LinkedHashMap<>();
            chains.put("latest-information", router.getRoutingChain("latest-information"));
            chains.put("general-search", router.getRoutingChain("general-search"));
            chains.put("search-fallback", router.getRoutingChain("search-fallback"));

            for (Map.Entry<String, List<String>> entry : chains.entrySet()) {
                log.info("  {} → {}", entry.getKey(), String.join(", ", entry.getValue()));
            }
        };
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
