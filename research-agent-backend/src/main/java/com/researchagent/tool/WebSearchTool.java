package com.researchagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
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
                // Unknown task type — fall back to SearXNG directly
                return searchViaSearxng(query);
            }

            return switch (backend) {
                case "web_search", "searxng" -> searchViaSearxng(query);
                default -> searchViaSearxng(query);  // SearXNG is the only local HTTP fallback
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

            // Tavily returns structured JSON — parse into formatted text for the LLM
            return parseTavilyJson(read(conn));

        } catch (Exception e) {
            return "Tavily error: " + e.getMessage();
        }
    }

    /**
     * SEARXNG (LOCAL MCP-FRIENDLY SEARCH ENGINE) — uses JSON format for structured results.
     */
    private String searchViaSearxng(String query) {
        try {
            String encoded = URLEncoder.encode(query, "UTF-8");

            HttpURLConnection conn = (HttpURLConnection)
                    new URL("http://localhost:9090/search?q=" + encoded + "&format=json").openConnection();

            conn.setRequestMethod("GET");

            String rawResponse = read(conn);

            return parseSearXngJson(rawResponse);

        } catch (Exception e) {
            return "SearXNG error: " + e.getMessage();
        }
    }

    /**
     * Parse SearXNG JSON response into structured text for the LLM.
     */
    private String parseSearXngJson(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return "No results from SearXNG";
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(rawResponse);

            // SearXNG JSON format: top-level "results" array (or nested under "results")
            JsonNode results = null;
            if (root.has("results")) {
                results = root.get("results");
            } else if (root.isArray()) {
                results = root;
            }

            if (results == null || !results.isArray() || results.isEmpty()) {
                return "SearXNG returned no results";
            }

            List<String> lines = new ArrayList<>();
            int idx = 0;
            for (JsonNode result : results) {
                String title = extractTitle(result);
                String url = extractUrl(result);
                String snippet = extractSnippet(result);

                lines.add((idx + 1) + ". " + title);
                if (url != null && !url.isBlank()) {
                    lines.add("   URL: " + url);
                }
                if (snippet != null && !snippet.isBlank()) {
                    lines.add("   Snippet: " + snippet);
                }
                idx++;
            }

            return String.join("\n", lines);

        } catch (Exception e) {
            // Fall back to raw response — may still be useful for the LLM
            return "SearXNG JSON parse failed, returning raw:\n" + rawResponse;
        }
    }

    private String extractTitle(JsonNode result) {
        if (result.has("title")) {
            return result.get("title").asText();
        }
        return "";
    }

    private String extractUrl(JsonNode result) {
        if (result.has("url")) {
            return result.get("url").asText();
        }
        // Try alternate field names used by SearXNG engines
        if (result.has("link") || result.has("href")) {
            return result.has("link") ? result.get("link").asText() : result.get("href").asText();
        }
        return "";
    }

    private String extractSnippet(JsonNode result) {
        // SearXNG JSON format uses "content" for snippet, some engines use "excerpt" or "description"
        if (result.has("content")) {
            return result.get("content").asText();
        }
        if (result.has("snippet") || result.has("excerpt") || result.has("description")) {
            String key = result.has("snippet") ? "snippet" : result.has("excerpt") ? "excerpt" : "description";
            return result.get(key).asText();
        }
        return "";
    }



    /**
     * Parse Tavily JSON response into structured text for the LLM.
     */
    private String parseTavilyJson(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return "No results from Tavily";
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(rawResponse);

            // Tavily JSON format: top-level "results" array
            JsonNode results = root.has("results") ? root.get("results") : null;
            
            if (results == null || !results.isArray() || results.isEmpty()) {
                return "Tavily returned no results";
            }

            List<String> lines = new ArrayList<>();
            int idx = 0;
            for (JsonNode result : results) {
                String title = extractTitle(result);
                String url = extractUrl(result);
                String snippet = extractSnippet(result);

                lines.add((idx + 1) + ". " + title);
                if (url != null && !url.isBlank()) {
                    lines.add("   URL: " + url);
                }
                if (snippet != null && !snippet.isBlank()) {
                    lines.add("   Snippet: " + snippet);
                }
                idx++;
            }

            return String.join("\n", lines);

        } catch (Exception e) {
            // Fall back to raw response — may still be useful for the LLM
            return "Tavily JSON parse failed, returning raw:\n" + rawResponse;
        }
    }

    /**
     * COMMON HTTP READER — increased line limit to capture full search results.
     */
    private String read(HttpURLConnection conn) throws Exception {
        return new BufferedReader(new InputStreamReader(conn.getInputStream()))
                .lines()
                .limit(1000)
                .collect(Collectors.joining("\n"));
    }
}