package com.researchagent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.stream.Collectors;

@Component
public class WebSearchTool {

    private final McpToolRouter router;

    public WebSearchTool(McpToolRouter router) {
        this.router = router;
    }

    /**
     * MAIN TOOL ENTRYPOINT (LLM calls this)
     */
    @Tool(description = "Search the web for information using intelligent routing.")
    public String search(
            @ToolParam(required = true, description = "Search query") String query,
            @ToolParam(required = false, description = "Task type: latest-information, general-search, search-fallback") String taskType
    ) {

        try {
            if (taskType == null || taskType.isBlank()) {
                taskType = classify(query);
            }

            String backend = router.getPreferredServer(taskType);

            if (backend == null) {
                return searchViaDuckDuckGo(query);
            }

            return switch (backend) {
                case "web_search" -> searchViaSearxng(query);
                case "searxng" -> searchViaSearxng(query);
                case "ddg_search" -> searchViaSearxng(query);
                default -> searchViaDuckDuckGo(query);
            };

        } catch (Exception e) {
            return "Search error: " + e.getMessage();
        }
    }

    /**
     * SIMPLE HEURISTIC CLASSIFIER (you can replace with LLM later)
     */
    private String classify(String query) {

        String q = query.toLowerCase();

        if (q.contains("latest") || q.contains("news") || q.contains("today")) {
            return "latest-information";
        }

        if (q.contains("how") || q.contains("what") || q.contains("explain")) {
            return "general-search";
        }

        return "search-fallback";
    }

    /**
     * TAVILY SEARCH
     */
    private String searchViaTavily(String query) {
        try {
            String apiKey = System.getenv("TAVILY_API_KEY");
            if (apiKey == null) {
                return "Tavily API key not configured";
            }

            HttpURLConnection conn = (HttpURLConnection)
                    new URL("https://api.tavily.com/search").openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);

            String body = "{\"query\":\"" + query + "\",\"max_results\":5}";
            conn.getOutputStream().write(body.getBytes());

            return read(conn);

        } catch (Exception e) {
            return "Tavily error: " + e.getMessage();
        }
    }

    /**
     * SEARXNG (LOCAL MCP-FRIENDLY SEARCH ENGINE)
     */
    private String searchViaSearxng(String query) {
        try {
            String encoded = URLEncoder.encode(query, "UTF-8");

            HttpURLConnection conn = (HttpURLConnection)
                    new URL("http://localhost:9090/search?q=" + encoded).openConnection();

            conn.setRequestMethod("GET");

            return read(conn);

        } catch (Exception e) {
            return "SearXNG error: " + e.getMessage();
        }
    }

    /**
     * DUCKDUCKGO FALLBACK
     */
    private String searchViaDuckDuckGo(String query) {
        try {
            String encoded = URLEncoder.encode(query, "UTF-8");

            HttpURLConnection conn = (HttpURLConnection)
                    new URL("https://html.duckduckgo.com/html/?q=" + encoded).openConnection();

            conn.setRequestMethod("GET");

            return read(conn);

        } catch (Exception e) {
            return "DDG error: " + e.getMessage();
        }
    }

    /**
     * COMMON HTTP READER
     */
    private String read(HttpURLConnection conn) throws Exception {
        return new BufferedReader(new InputStreamReader(conn.getInputStream()))
                .lines()
                .limit(200)
                .collect(Collectors.joining("\n"));
    }
}