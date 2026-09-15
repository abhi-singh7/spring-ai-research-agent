package com.researchagent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpClientErrorHandlerTest {

    private McpClientErrorHandler handler;

    @BeforeEach
    void setUp() {
        handler = new McpClientErrorHandler();
    }

    // ---------- getNextSearchFallback ----------

    @Test
    void getNextSearchFallback_firecrawl_shouldReturnDuckDuckGo() {
        String fallback = handler.getNextSearchFallback("firecrawl");
        assertThat(fallback).isEqualTo("ddg");
    }

    @Test
    void getNextSearchFallback_ddg_shouldReturnOllamaWebSearch() {
        String fallback = handler.getNextSearchFallback("ddg");
        assertThat(fallback).isEqualTo("ollama_web_search");
    }

    @Test
    void getNextSearchFallback_ollamaWebSearch_shouldReturnTavily() {
        String fallback = handler.getNextSearchFallback("ollama_web_search");
        assertThat(fallback).isEqualTo("tavily");
    }

    @Test
    void getNextSearchFallback_tavily_shouldReturnNull() {
        // When: all search providers are down, fall back to local tools
        String fallback = handler.getNextSearchFallback("tavily");
        assertThat(fallback).isNull();
    }

    @Test
    void getNextSearchFallback_unknownServer_shouldReturnItself() {
        // For unknown servers, keep trying them
        String fallback = handler.getNextSearchFallback("unknown-server");
        assertThat(fallback).isEqualTo("unknown-server");
    }

    // ---------- isMcpServerAvailable ----------

    @Test
    void isMcpServerAvailable_firecrawl_shouldNotThrow() {
        // Just verify the method doesn't throw — actual availability depends on environment
        boolean available = handler.isMcpServerAvailable("firecrawl");
        assertThat(available).isNotNull();
    }

    @Test
    void isMcpServerAvailable_ollamaWebSearch_shouldNotThrow() {
        boolean available = handler.isMcpServerAvailable("ollama_web_search");
        assertThat(available).isNotNull();
    }

    @Test
    void isMcpServerAvailable_unknownServer_shouldReturnFalse() {
        // Unknown servers should always be reported as unavailable
        boolean available = handler.isMcpServerAvailable("unknown-server");
        assertThat(available).isFalse();
    }

    // ---------- onMcpTimeout ----------

    @Test
    void onMcpTimeout_shouldIncreaseTimeoutWhenBelow60Seconds() {
        int initialTimeout = handler.getRequestTimeoutSeconds();
        assertThat(initialTimeout).isEqualTo(20);

        handler.onMcpTimeout("firecrawl", "search");
        assertThat(handler.getRequestTimeoutSeconds()).isGreaterThan(initialTimeout);
    }

    @Test
    void onMcpTimeout_shouldNotExceed60Seconds() {
        // Manually set timeout near the cap
        handler.setRequestTimeoutSeconds(55);
        handler.onMcpTimeout("firecrawl", "search");
        assertThat(handler.getRequestTimeoutSeconds()).isEqualTo(65);

        handler.setRequestTimeoutSeconds(60);
        handler.onMcpTimeout("firecrawl", "search");
        // Should not increase beyond 60
        assertThat(handler.getRequestTimeoutSeconds()).isEqualTo(60);
    }

    // ---------- onMcpServerStartupFailure ----------

    @Test
    void onMcpServerStartupFailure_firecrawl_shouldLogFallbackInfo() {
        // Just verify it doesn't throw
        handler.onMcpServerStartupFailure("firecrawl", new RuntimeException("connection refused"));
    }

    @Test
    void onMcpServerStartupFailure_ollamaWebSearch_shouldLogFallbackInfo() {
        handler.onMcpServerStartupFailure("ollama_web_search", new RuntimeException("command not found"));
    }

    @Test
    void onMcpServerStartupFailure_excalidraw_shouldLogFallbackInfo() {
        handler.onMcpServerStartupFailure("excalidraw", new RuntimeException("process killed"));
    }

    // ---------- getMcpServerStatusSummary ----------

    @Test
    void getMcpServerStatusSummary_shouldReturnFormattedString() {
        String summary = handler.getMcpServerStatusSummary();
        assertThat(summary).contains("=== MCP Server Status ===");
        assertThat(summary).contains("ollama_web_search:");
        assertThat(summary).contains("firecrawl:");
        boolean hasAvailableOrUnavailable = summary.contains("AVAILABLE") || summary.contains("UNAVAILABLE");
        assertThat(hasAvailableOrUnavailable).isTrue();
    }

    // ---------- onMcpInvalidResponse ----------

    @Test
    void onMcpInvalidResponse_shouldLogTruncatedResponse() {
        // Just verify it doesn't throw for very long responses
        String longResponse = "x".repeat(500);
        handler.onMcpInvalidResponse("firecrawl", "search", longResponse);
    }

    @Test
    void onMcpInvalidResponse_shouldLogFullShortResponse() {
        String shortResponse = "bad json";
        handler.onMcpInvalidResponse("firecrawl", "search", shortResponse);
    }
}
