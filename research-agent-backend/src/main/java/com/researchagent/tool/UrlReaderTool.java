package com.researchagent.tool;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Tool that fetches and extracts readable content from URLs returned by web search results.
 * Uses Jsoup to parse HTML and extract the main article body, stripping scripts, styles,
 * navigation elements, and other non-content markup. When Jsoup fails or finds no readable
 * body (bot-walled or JS-heavy pages), it escalates to the self-hosted Firecrawl API
 * (POST {firecrawl-base-url}/v2/scrape) which renders the page and returns clean markdown.
 */
@Component
public class UrlReaderTool {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    /** Self-hosted Firecrawl scrapes can be slow on heavy pages — allow up to 60s of read time. */
    private static final int SCRAPE_READ_TIMEOUT_MS = 60_000;
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; ResearchBot/1.0)";

    private final String firecrawlBaseUrl;

    public UrlReaderTool(@Value("${app.search.firecrawl-base-url:http://localhost:3002}") String firecrawlBaseUrl) {
        this.firecrawlBaseUrl = firecrawlBaseUrl;
    }

    /**
     * Fetch and read full page content from a URL, extracting only the readable text.
     */
    @Tool(description = "Read the full content of a web page, extracting readable article body text while stripping scripts, styles, and navigation elements.")
    public String read_url(
            @ToolParam(required = true, description = "URL to read") String url,
            @ToolParam(required = false, description = "Maximum length of extracted text (default: 10000)") Integer maxContentLength
    ) {
        int maxLength = (maxContentLength != null && maxContentLength > 0) ? maxContentLength : 10000;

        String content = null;
        String jsoupError = null;
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; ResearchBot/1.0)")
                    .timeout(15000) // 15 second timeout for all network operations (connect + read)
                    .followRedirects(true)
                    .maxBodySize(2 * 1024 * 1024) // 2MB max body size
                    .get();

            content = extractMainContent(doc);
        } catch (Exception e) {
            jsoupError = e.getMessage();
        }

        if (content == null || content.isBlank()) {
            // Jsoup failed or found no readable body — escalate to the self-hosted Firecrawl scraper.
            String scraped = scrapeViaFirecrawl(url);
            if (scraped != null && !scraped.isBlank()) {
                return truncate(scraped, maxLength);
            }
            if (jsoupError == null) {
                return ""; // no readable content from any reader — LLM can use search snippets instead of failing
            }
            return "Error reading URL " + url + ": " + jsoupError;
        }

        return truncate(content, maxLength);
    }

    /** Truncate to the requested length with ellipsis. */
    private String truncate(String content, int maxLength) {
        if (content.length() > maxLength) {
            content = content.substring(0, maxLength).trim();
            content += "...";
        }
        return content;
    }

    /**
     * FIRECRAWL — self-hosted Firecrawl API (POST {base}/v2/scrape) with markdown format. Fallback for pages
     * Jsoup cannot read. Request body: {"url": ..., "formats": ["markdown"]}; response shape:
     * {"success": true, "data": {"markdown": "...", "metadata": {...}}}. Returns cleaned markdown text, or
     * null when the scrape fails or yields nothing (caller decides how to report).
     */
    private String scrapeViaFirecrawl(String url) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String body = mapper.writeValueAsString(Map.of("url", url, "formats", List.of("markdown")));

            HttpURLConnection conn = (HttpURLConnection) new URL(firecrawlBaseUrl + "/v2/scrape").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(SCRAPE_READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status / 100 != 2) {
                return null;
            }

            String rawResponse = readBody(conn);
            JsonNode root = mapper.readTree(rawResponse);
            if (!root.path("success").asBoolean(false)) {
                return null;
            }

            String markdown = root.path("data").path("markdown").asText("");
            return cleanContent(markdown, 15000);

        } catch (Exception e) {
            return null;
        }
    }

    /** Bounded UTF-8 read of a response body (line-capped so a pathological page can't blow memory). */
    private String readBody(HttpURLConnection conn) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null && lines < 20000) {
                sb.append(line).append('\n');
                lines++;
            }
            return sb.toString();
        }
    }

    /**
     * Extract the main article body from a document using heuristic-based content detection.
     */
    private String extractMainContent(Document doc) {
        // Strategy 1: Look for common article/content IDs/classes in priority order
        String[] selectors = new String[]{
                "#article",
                "#content",
                "#main-content",
                "#post-body",
                ".post-content",
                ".entry-content",
                "article",
                "[class*='article']",
                "[class*='content']"
        };

        for (String selector : selectors) {
            try {
                var elements = doc.select(selector);
                if (!elements.isEmpty()) {
                    return cleanContent(elements.get(0).text(), 15000);
                }
            } catch (Exception e) {
                // Try next selector
            }
        }

        // Strategy 2: Fall back to removing scripts/styles and getting all body text
        var content = doc.select("body").first();
        if (content != null) {
            String text = cleanContent(content.text(), 15000);
            // Only return body text if it has meaningful content (minimum 50 chars to avoid noise)
            if (!text.isEmpty() && text.length() > 50) {
                return text;
            }
        }

        // Strategy 3: Last resort — get all body text without any filtering
        String lastResort = cleanContent(doc.body().text(), 15000);
        if (!lastResort.isEmpty()) {
            return lastResort;
        }

        return ""; // Empty string instead of error — LLM can use search snippets instead
    }

    /**
     * Clean extracted content by removing excessive whitespace and normalizing.
     */
    private String cleanContent(String rawText, int maxLength) {
        if (rawText == null) {
            return "";
        }

        // Normalize whitespace: collapse multiple spaces/newlines into single space
        String cleaned = rawText.replaceAll("\\s+", " ").trim();

        // Remove any zero-width characters and other invisible chars
        cleaned = cleaned.replaceAll("[\\p{Cntrl}&&[^\\t]]", "");

        if (cleaned.length() > maxLength) {
            return cleaned.substring(0, maxLength).trim() + "...";
        }

        return cleaned;
    }
}
