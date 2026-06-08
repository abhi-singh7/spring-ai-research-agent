package com.researchagent.tool;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Tool that fetches and extracts readable content from URLs returned by web search results.
 * Uses Jsoup to parse HTML and extract the main article body, stripping scripts, styles,
 * navigation elements, and other non-content markup.
 */
@Component
public class UrlReaderTool {

    /**
     * Fetch and read full page content from a URL, extracting only the readable text.
     */
    @Tool(description = "Read the full content of a web page, extracting readable article body text while stripping scripts, styles, and navigation elements.")
    public String read_url(
            @ToolParam(required = true, description = "URL to read") String url,
            @ToolParam(required = false, description = "Maximum length of extracted text (default: 10000)") Integer maxContentLength
    ) {
        try {
            int maxLength = (maxContentLength != null && maxContentLength > 0) ? maxContentLength : 10000;

            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; ResearchBot/1.0)")
                    .timeout(15000) // 15 second timeout
                    .followRedirects(true)
                    .maxBodySize(2 * 1024 * 1024) // 2MB max body size
                    .get();

            String content = extractMainContent(doc);

            if (content == null || content.isBlank()) {
                return "No readable content found at: " + url;
            }

            // Truncate to requested length with ellipsis
            if (content.length() > maxLength) {
                content = content.substring(0, maxLength).trim();
                content += "...";
            }

            return content;

        } catch (Exception e) {
            return "Error reading URL " + url + ": " + e.getMessage();
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

        // Strategy 2: Fall back to removing scripts/styles and getting all text
        var content = doc.select("body").first();
        if (content != null) {
            return cleanContent(content.text(), 15000);
        }

        // Strategy 3: Last resort — get all body text
        return cleanContent(doc.body().text(), 15000);
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
