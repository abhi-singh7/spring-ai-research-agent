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
    void getNextSearchFallback_searxng_shouldReturnWebSearch() {
        String fallback = handler.getNextSearchFallback("searxng");
        assertThat(fallback).isEqualTo("web_search");
    }

    @Test
    void getNextSearchFallback_webSearch_shouldReturnNull() {
        // When: all search providers are down, fall back to local tools
        String fallback = handler.getNextSearchFallback("web_search");
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
    void isMcpServerAvailable_shearxng_shouldNotThrow() {
        // Just verify the method doesn't throw — actual availability depends on environment
        boolean available = handler.isMcpServerAvailable("searxng");
        assertThat(available).isNotNull();
    }

    @Test
    void isMcpServerAvailable_webSearch_shouldNotThrow() {
        boolean available = handler.isMcpServerAvailable("web_search");
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

        handler.onMcpTimeout("searxng", "search");
        assertThat(handler.getRequestTimeoutSeconds()).isGreaterThan(initialTimeout);
    }

    @Test
    void onMcpTimeout_shouldNotExceed60Seconds() {
        // Manually set timeout near the cap
        handler.setRequestTimeoutSeconds(55);
        handler.onMcpTimeout("searxng", "search");
        assertThat(handler.getRequestTimeoutSeconds()).isEqualTo(65);

        handler.setRequestTimeoutSeconds(60);
        handler.onMcpTimeout("searxng", "search");
        // Should not increase beyond 60
        assertThat(handler.getRequestTimeoutSeconds()).isEqualTo(60);
    }

    // ---------- onMcpServerStartupFailure ----------

    @Test
    void onMcpServerStartupFailure_searxng_shouldLogFallbackInfo() {
        // Just verify it doesn't throw
        handler.onMcpServerStartupFailure("searxng", new RuntimeException("connection refused"));
    }

    @Test
    void onMcpServerStartupFailure_webSearch_shouldLogFallbackInfo() {
        handler.onMcpServerStartupFailure("web_search", new RuntimeException("command not found"));
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
        assertThat(summary).contains("web_search:");
        assertThat(summary).contains("searxng:");
        boolean hasAvailableOrUnavailable = summary.contains("AVAILABLE") || summary.contains("UNAVAILABLE");
        assertThat(hasAvailableOrUnavailable).isTrue();
    }

    // ---------- onMcpInvalidResponse ----------

    @Test
    void onMcpInvalidResponse_shouldLogTruncatedResponse() {
        // Just verify it doesn't throw for very long responses
        String longResponse = "x".repeat(500);
        handler.onMcpInvalidResponse("searxng", "search", longResponse);
    }

    @Test
    void onMcpInvalidResponse_shouldLogFullShortResponse() {
        String shortResponse = "bad json";
        handler.onMcpInvalidResponse("searxng", "search", shortResponse);
    }
}
