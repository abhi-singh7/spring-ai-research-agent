package com.researchagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Error handling and fallback chain for MCP server failures.
 * 
 * When an MCP server is unavailable, this handler provides graceful degradation:
 * - SearXNG unavailable → Web Search (MCP)
 * - Timeout errors → retry with longer timeout or skip
 * - Invalid responses → log error, continue without that tool
 */
@Component
public class McpClientErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(McpClientErrorHandler.class);

    // Default timeout in seconds for MCP client requests (Spring AI default is 20s)
    private int requestTimeoutSeconds = 20;

    /**
     * Handle MCP server startup failure gracefully.
     * Spring AI auto-configured clients handle this by simply not registering tools from unavailable servers,
     * but we log for visibility.
     */
    public void onMcpServerStartupFailure(String serverName, Throwable error) {
        log.error("MCP server '{}' failed to start: {}", serverName, error.getMessage());
        
        // Determine fallback based on server type
        if (serverName.equals("searxng")) {
            log.warn("SearXNG unavailable — search will fall back to Web Search MCP");
        } else if (serverName.equals("web_search")) {
            log.warn("Web Search MCP server unavailable — search will use local tools as fallback");
        } else if (serverName.equals("excalidraw")) {
            log.warn("Excalidraw MCP server unavailable — diagram generation will not be available");
        }
    }

    /**
     * Determine the next fallback server in the chain when a search provider is unavailable.
     */
    public String getNextSearchFallback(String failedServer) {
        if (failedServer.equals("searxng")) {
            log.info("Falling back from SearXNG to Web Search MCP");
            return "web_search";
        } else if (failedServer.equals("web_search")) {
            log.warn("All search providers unavailable — falling back to local tools");
            return null; // No more fallbacks, will use local WebSearchTool
        }
        return failedServer; // Unknown server, keep trying it
    }

    /**
     * Handle MCP tool execution timeout.
     */
    public void onMcpTimeout(String serverName, String toolName) {
        log.warn("MCP tool '{}' from server '{}' timed out after {} seconds", toolName, serverName, requestTimeoutSeconds);
        
        // Retry with longer timeout for the next attempt
        if (requestTimeoutSeconds < 60) {
            requestTimeoutSeconds += 10;
            log.info("Increasing MCP client request timeout to {} seconds", requestTimeoutSeconds);
        }
    }

    /**
     * Handle invalid/unknown MCP tool responses.
     */
    public void onMcpInvalidResponse(String serverName, String toolName, String response) {
        log.error("Invalid response from MCP server '{}' for tool '{}': {}", serverName, toolName, 
            response.length() > 200 ? response.substring(0, 200) + "..." : response);
    }

    /**
     * Check if an MCP server is likely available based on its configuration.
     */
    public boolean isMcpServerAvailable(String serverName) {
        switch (serverName) {
            case "searxng":
                return checkSearXNGAvailability();
            case "web_search":
                return checkWebSearchAvailability();
            default:
                return false;
        }
    }

    private boolean checkSearXNGAvailability() {
        try {
            java.net.HttpURLConnection connection = 
                (java.net.HttpURLConnection) new java.net.URL("http://localhost:8080").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(2000);
            int code = connection.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkWebSearchAvailability() {
        // Check if the Python script exists and is executable
        String scriptPath = "/home/abhi/ollama_web_search.py";
        java.io.File file = new java.io.File(scriptPath);
        return file.exists() && file.canRead();
    }

    /**
     * Get a summary of available MCP servers for logging.
     */
    public String getMcpServerStatusSummary() {
        StringBuilder sb = new StringBuilder("\n=== MCP Server Status ===\n");
        
        sb.append("web_search: ").append(isMcpServerAvailable("web_search") ? "AVAILABLE" : "UNAVAILABLE").append("\n");
        sb.append("searxng:    ").append(isMcpServerAvailable("searxng") ? "AVAILABLE" : "UNAVAILABLE").append("\n");
        
        sb.append("================================\n");
        return sb.toString();
    }

    /**
     * Get the configured request timeout in seconds.
     */
    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    /**
     * Set the request timeout (useful for adjusting at runtime).
     */
    public void setRequestTimeoutSeconds(int timeout) {
        this.requestTimeoutSeconds = timeout;
    }
}
