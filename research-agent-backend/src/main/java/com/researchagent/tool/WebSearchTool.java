package com.researchagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Search tool exposed to the LLM.
 *
 * <p>Executes the router's ordered backend chain IN ONE CALL: tries SearXNG first, then DuckDuckGo (an
 * independent engine), then Ollama Web Search (hosted API), then Tavily (hosted search API) — moving on
 * whenever a backend errors or returns no results. The returned text is always DECISIVE — either formatted
 * results from the first successful backend, or an explicit "no results from any backend" verdict listing
 * per-backend reasons — so the LLM never has to (or wants to) retry the same dead search method in a loop.</p>
 */
@Component
@Slf4j
public class WebSearchTool {


    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_RESULTS = 8;
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; ResearchAgent/1.0)";

    /** DuckDuckGo HTML result link: <a ... href="/l/?uddg=<urlencoded>" ...>title</a> */
    private static final Pattern DDG_RESULT_LINK = Pattern.compile(
            "<a\\s[^>]*href=\"/l/\\?uddg=([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);

    /** DuckDuckGo HTML snippet: <span class="result__snippet">...</span> */
    private static final Pattern DDG_SNIPPET = Pattern.compile(
            "class=\"result__snippet\"[^>]*>(.*?)</span>", Pattern.DOTALL);

    private final McpToolRouter router;
    private final String searxngBaseUrl;
    private final String ddgBaseUrl;
    private final String ollamaBaseUrl;
    private final String ollamaApiKey;
    private final String tavilyBaseUrl;
    private final String tavilyApiKey;

    @Autowired
    public WebSearchTool(McpToolRouter router,
                          @Value("${app.search.ollama-base-url:https://ollama.com}") String ollamaBaseUrl,
                          @Value("${app.search.ollama-api-key:}") String ollamaApiKey,
                          @Value("${app.search.tavily-api-key:}") String tavilyApiKey) {
        this(router, "http://localhost:9090", "https://duckduckgo.com", ollamaBaseUrl, ollamaApiKey,
                "https://api.tavily.com", tavilyApiKey);
    }

    /** Package-private constructor for tests — endpoints overridable so no real network is needed. */
    WebSearchTool(McpToolRouter router, String searxngBaseUrl, String ddgBaseUrl,
                  String ollamaBaseUrl, String ollamaApiKey,
                  String tavilyBaseUrl, String tavilyApiKey) {
        this.router = router;
        this.searxngBaseUrl = searxngBaseUrl;
        this.ddgBaseUrl = ddgBaseUrl;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.ollamaApiKey = (ollamaApiKey == null || ollamaApiKey.isBlank()) ? "" : ollamaApiKey.strip();
        this.tavilyBaseUrl = tavilyBaseUrl;
        this.tavilyApiKey = (tavilyApiKey == null || tavilyApiKey.isBlank()) ? "" : tavilyApiKey.strip();
    }

    /** Outcome of one backend attempt: either formatted results or a human-readable failure reason. */
    private record SearchOutcome(boolean found, String payload) {
        static SearchOutcome ok(String resultsText) { return new SearchOutcome(true, resultsText); }
        static SearchOutcome fail(String reason) { return new SearchOutcome(false, reason); }
    }

    /**
     * MAIN TOOL ENTRYPOINT (LLM calls this). Tries every backend in the routed chain until one yields results.
     */
    @Tool(description = "Search the web for information using intelligent routing across multiple search engines (SearXNG → DuckDuckGo → Ollama → Tavily).")
    public String search(
            @ToolParam(required = true, description = "Search query") String query,
            @ToolParam(required = false, description = "Task type: latest-information, general-search, search-fallback") String taskType
    ) {

        try {
            if (taskType == null || taskType.isBlank()) {
                taskType = classify(query);
            }

            List<String> backends = router.resolveBackends(taskType);
            List<String> failures = new ArrayList<>();

            for (String backend : backends) {
                SearchOutcome outcome;
                switch (backend) {
                    case "searxng" -> outcome = searchViaSearxng(query);
                    case "ddg", "duckduckgo" -> outcome = searchViaDuckDuckGo(query);
                    case "ollama_web_search", "ollama" -> outcome = searchViaOllamaWebSearch(query);
                    case "tavily" -> outcome = searchViaTavily(query);
                    default -> {
                        log.warn("Unknown backend '{}' in routing chain — skipping (query: {})", backend, query);
                        failures.add(backend + ": unsupported");
                        continue;
                    }
                }

                if (outcome.found()) {
                    log.info("Search via '{}' succeeded for query: {}", backend, query);
                    return outcome.payload();
                }
                failures.add(backend + ": " + outcome.payload());
            }

            // All backends exhausted in this single call — return a DECISIVE verdict so the LLM does not retry.
            log.warn("All search backends failed for query '{}': {}", query, String.join("; ", failures));
            return "No results from any search backend (" + String.join("; ", failures)
                    + "). Do NOT retry this query — continue with the information already gathered.";

        } catch (Exception e) {
            log.error("Search tool failed for query '{}': {}", query, e.getMessage(), e);
            return "Search error: " + e.getMessage()
                    + ". Do NOT retry this query — continue with the information already gathered.";
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
     * SEARXNG — local SearXNG instance, JSON format. The preferred engine per the routing chain.
     */
    private SearchOutcome searchViaSearxng(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);

            HttpURLConnection conn = openGet(searxngBaseUrl + "/search?q=" + encoded + "&format=json");
            int status = conn.getResponseCode();
            if (status / 100 != 2) {
                return SearchOutcome.fail("HTTP " + status);
            }

            String rawResponse = read(conn);
            return parseSearXngJson(rawResponse);

        } catch (Exception e) {
            return SearchOutcome.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * OLLAMA WEB SEARCH — Ollama's hosted web_search API ({base}/api/web_search), the final escalation target
     * after SearXNG and DuckDuckGo both fail or are rate-limited. Requires a Bearer token (OLLAMA_API_KEY);
     * without one it degrades to an explicit reason instead of touching the network.
     */
    private SearchOutcome searchViaOllamaWebSearch(String query) {
        try {
            if (ollamaApiKey.isEmpty()) {
                return SearchOutcome.fail("OLLAMA_API_KEY not configured");
            }

            ObjectMapper mapper = new ObjectMapper();
            String body = mapper.writeValueAsString(Map.of("query", query, "max_results", MAX_RESULTS));

            HttpURLConnection conn = openJsonPost(ollamaBaseUrl + "/api/web_search",
                    "Bearer " + ollamaApiKey, body);
            int status = conn.getResponseCode();
            if (status / 100 != 2) {
                return SearchOutcome.fail("HTTP " + status + ": " + readHttpError(conn));
            }

            String rawResponse = read(conn);
            return parseResultsJson(rawResponse, "Ollama");

        } catch (Exception e) {
            return SearchOutcome.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * DUCKDUCKGO — HTML endpoint, an independent index used when SearXNG yields nothing. No API key required.
     */
    private SearchOutcome searchViaDuckDuckGo(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);

            HttpURLConnection conn = openGet(ddgBaseUrl + "/html/?q=" + encoded);
            int status = conn.getResponseCode();
            if (status / 100 != 2) {
                return SearchOutcome.fail("HTTP " + status);
            }

            String html = read(conn);
            List<String> lines = parseDuckDuckGoHtml(html);
            if (lines.isEmpty()) {
                return SearchOutcome.fail("returned no results");
            }

            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append('\n');
            }
            return SearchOutcome.ok(sb.toString());

        } catch (Exception e) {
            return SearchOutcome.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * TAVILY — Tavily Search API ({base}/search), the final escalation target after SearXNG, DuckDuckGo and
     * Ollama Web Search all fail or are rate-limited. Requires a Bearer token (TAVILY_API_KEY); without one it
     * degrades to an explicit reason instead of touching the network.
     */
    private SearchOutcome searchViaTavily(String query) {
        try {
            if (tavilyApiKey.isEmpty()) {
                return SearchOutcome.fail("TAVILY_API_KEY not configured");
            }

            ObjectMapper mapper = new ObjectMapper();
            String body = mapper.writeValueAsString(Map.of("query", query, "max_results", MAX_RESULTS));

            HttpURLConnection conn = openJsonPost(tavilyBaseUrl + "/search",
                    "Bearer " + tavilyApiKey, body);
            int status = conn.getResponseCode();
            if (status / 100 != 2) {
                return SearchOutcome.fail("HTTP " + status + ": " + readHttpError(conn));
            }

            String rawResponse = read(conn);
            return parseResultsJson(rawResponse, "Tavily");

        } catch (Exception e) {
            return SearchOutcome.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Parse a {@code {results:[{title,url,content}]}} payload (Ollama web_search and Tavily search both use this
     * shape) into structured text for the LLM.
     */
    private SearchOutcome parseResultsJson(String rawResponse, String engineLabel) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return SearchOutcome.fail("empty response");
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(rawResponse);
            JsonNode results = root.has("results") ? root.get("results") : null;

            if (results == null || !results.isArray() || results.isEmpty()) {
                return SearchOutcome.fail("returned no results");
            }

            List<String> lines = new ArrayList<>();
            int idx = 0;
            for (JsonNode result : results) {
                if (idx >= MAX_RESULTS) break;
                String title = result.path("title").asText("");
                String url = result.path("url").asText("");
                String snippet = result.path("content").asText("");

                lines.add((idx + 1) + ". " + title);
                if (!url.isBlank()) {
                    lines.add("   URL: " + url);
                }
                if (!snippet.isBlank()) {
                    lines.add("   Snippet: " + snippet);
                }
                idx++;
            }

            return SearchOutcome.ok(String.join("\n", lines));

        } catch (Exception e) {
            // Fall back to raw response — may still be useful for the LLM if it looks like result data.
            String trimmed = rawResponse.strip();
            if (trimmed.isEmpty() || !looksLikeResults(trimmed)) {
                return SearchOutcome.fail("unparseable JSON");
            }
            return SearchOutcome.ok(engineLabel + " raw response:\n" + trimmed);
        }
    }

    /**
     * Parse DuckDuckGo HTML results: result anchors (/l/?uddg=...) paired with snippets, in page order.
     */
    private List<String> parseDuckDuckGoHtml(String html) {
        if (html == null || html.isBlank()) return List.of();

        // Capture [urlEncoded, title] pairs immediately — the Matcher must advance per match.
        List<String[]> found = new ArrayList<>();
        Matcher links = DDG_RESULT_LINK.matcher(html);
        while (links.find()) {
            if (found.size() >= MAX_RESULTS) break;
            found.add(new String[]{links.group(1), stripHtml(links.group(2))});
        }
        if (found.isEmpty()) return List.of();

        List<String> snippets = new ArrayList<>();
        Matcher snips = DDG_SNIPPET.matcher(html);
        while (snips.find() && snippets.size() < MAX_RESULTS) {
            snippets.add(stripHtml(snips.group(1)));
        }

        LinkedHashSet<String> seenUrls = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();
        int idx = 0;
        for (String[] link : found) {
            String url;
            try {
                url = java.net.URLDecoder.decode(link[0], StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!seenUrls.add(url)) continue;

            String title = link[1];
            int n = idx + 1;
            lines.add(n + ". " + title);
            if (!url.isBlank()) lines.add("   URL: " + url);
            if (idx < snippets.size() && !snippets.get(idx).isBlank()) {
                lines.add("   Snippet: " + snippets.get(idx));
            }
            idx++;
        }
        return lines;
    }

    /** Strip HTML tags and decode common entities so titles/snippets read cleanly for the LLM. */
    private String stripHtml(String fragment) {
        if (fragment == null) return "";
        return fragment.replaceAll("<[^>]*>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim();
    }

    /**
     * Parse SearXNG JSON response into structured text for the LLM.
     */
    private SearchOutcome parseSearXngJson(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return SearchOutcome.fail("empty response");
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
                return SearchOutcome.fail("returned no results");
            }

            List<String> lines = new ArrayList<>();
            int idx = 0;
            for (JsonNode result : results) {
                if (idx >= MAX_RESULTS) break;
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

            return SearchOutcome.ok(String.join("\n", lines));

        } catch (Exception e) {
            // Fall back to raw response — may still be useful for the LLM if it looks like result data.
            String trimmed = rawResponse.strip();
            if (trimmed.isEmpty() || !looksLikeResults(trimmed)) {
                return SearchOutcome.fail("unparseable JSON");
            }
            return SearchOutcome.ok("SearXNG raw response:\n" + trimmed);
        }
    }

    /** Heuristic: does the raw payload look like it carries result data worth forwarding to the LLM? */
    private boolean looksLikeResults(String raw) {
        String lower = raw.toLowerCase();
        return lower.contains("url") || lower.contains("http") || lower.contains("\"results\"");
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
     * Open a JSON POST connection with bounded timeouts and an Authorization header; writes the body before
     * returning so callers can inspect the response status directly.
     */
    private HttpURLConnection openJsonPost(String url, String authorizationHeader, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        if (authorizationHeader != null && !authorizationHeader.isBlank()) {
            conn.setRequestProperty("Authorization", authorizationHeader);
        }
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    /** Bounded read of the error stream — surfaces API error messages (e.g. "unauthorized") in failure reasons. */
    private String readHttpError(HttpURLConnection conn) {
        InputStream err = null;
        try {
            err = conn.getErrorStream();
            if (err == null) return "";
            byte[] bytes = err.readNBytes(500);
            return new String(bytes, StandardCharsets.UTF_8).strip();
        } catch (Exception e) {
            return "";
        } finally {
            if (err != null) {
                try { err.close(); } catch (IOException ignored) { }
            }
        }
    }

    /** Open a GET connection with bounded timeouts so a dead/hung backend can never block the pipeline forever. */
    private HttpURLConnection openGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        return conn;
    }

    /** COMMON HTTP READER — bounded line limit, UTF-8. */
    private String read(HttpURLConnection conn) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null && lines < 1000) {
                sb.append(line).append('\n');
                lines++;
            }
            return sb.toString();
        }
    }
}
