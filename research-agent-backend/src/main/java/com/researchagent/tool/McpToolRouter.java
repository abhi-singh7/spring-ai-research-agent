package com.researchagent.tool;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Ordered fallback chains for search backends.
 *
 * <p>The chain is EXECUTED by {@link WebSearchTool}: it tries each backend in order and moves on when the
 * previous one errors or returns no results. A single tool call therefore always ends with a decisive answer —
 * either results from whichever backend first succeeded, or an explicit "all backends exhausted" verdict — so
 * the LLM never re-invokes search against a known-dead backend in a loop.</p>
 */
@Component
@Slf4j
public class McpToolRouter {

    /** Default chain used when the requested task type is unknown/null. */
    public static final List<String> DEFAULT_BACKEND_CHAIN = List.of("searxng", "ddg", "ollama_web_search", "tavily");

    private final Map<String, List<String>> routingMap = new LinkedHashMap<>();

    public McpToolRouter() {

        // searxng (local SearXNG instance) is the preferred engine; duckduckgo is the independent fallback
        // that keeps working even when SearXNG has no results or its upstream engines are rate-limited;
        // ollama_web_search (Ollama's hosted search API) is the third escalation target;
        // tavily (Tavily Search API) is the final escalation target.
        routingMap.put("latest-information", List.of(
                "searxng",
                "ddg",
                "ollama_web_search",
                "tavily"
        ));

        routingMap.put("general-search", List.of(
                "searxng",
                "ddg",
                "ollama_web_search",
                "tavily"
        ));

        routingMap.put("search-fallback", List.of(
                "searxng",
                "ddg",
                "ollama_web_search",
                "tavily"
        ));

        log.info("McpToolRouter initialized with {} routes", routingMap.size());
    }

    public String getPreferredServer(String taskType) {
        List<String> servers = resolveBackends(taskType);
        return servers.isEmpty() ? null : servers.getFirst();
    }

    /**
     * Ordered list of backends to EXECUTE for the given task type. Unknown/null types fall back to the default
     * chain instead of an empty list — a search must always have at least one backend to try.
     */
    public List<String> resolveBackends(String taskType) {
        String key = (taskType == null || taskType.isBlank()) ? "search-fallback" : taskType.trim();
        List<String> chain = routingMap.get(key);
        if (chain == null || chain.isEmpty()) {
            return DEFAULT_BACKEND_CHAIN;
        }
        // Defensive de-duplication, preserving order.
        LinkedHashSet<String> ordered = new LinkedHashSet<>(chain);
        return List.copyOf(ordered);
    }

    public List<String> getRoutingChain(String taskType) {
        return resolveBackends(taskType);
    }
}
