package com.researchagent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.stream.Collectors;

/**
 * Spring AI Tool definitions for web search and content reading.
 * The LLM will autonomously decide when to invoke these tools during generation.
 */
@Component
public class WebSearchTool {

    /**
     * Search the web for information about a topic. Returns up to 5 results with titles, URLs, and descriptions.
     */
    @Tool(description = "Search the web for information about a topic. Returns a list of search results with titles, URLs, and brief descriptions.")
    public String search(
            @ToolParam(required = true, description = "The search query to perform") String query) {

        try {
            // Use Tavily API if configured, otherwise use DuckDuckGo-lite approach
            String tavilyApiKey = System.getenv("TAVILY_API_KEY");
            if (tavilyApiKey != null && !tavilyApiKey.isEmpty()) {
                return searchViaTavily(query);
            }

            // Fallback: simple HTTP search via web scraping
            return searchViaHttp(query);
        } catch (Exception e) {
            return "Error searching for '" + query + "': " + e.getMessage();
        }
    }

    /**
     * Read the content of a URL and return it as text. Use this to read web pages for detailed information.
     */
    @Tool(description = "Read the content of a URL and return it as text. Use this to read web pages for detailed information.")
    public String readUrl(
            @ToolParam(required = true, description = "The URL to fetch content from") String url) {

        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 Research Agent");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return "Failed to fetch URL: HTTP " + responseCode;
            }

            String content = new BufferedReader(new InputStreamReader(connection.getInputStream()))
                    .lines()
                    .collect(Collectors.joining("\n"));

            // Truncate very long content to fit within context window limits
            if (content.length() > 8000) {
                return content.substring(0, 8000);
            }

            return content;
        } catch (Exception e) {
            return "Error reading URL '" + url + "': " + e.getMessage();
        }
    }

    private String searchViaTavily(String query) {
        try {
            // Tavily Search API call
            HttpURLConnection connection = (HttpURLConnection) new URL("https://api.tavily.com/search").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + System.getenv("TAVILY_API_KEY"));

            String requestBody = "{\"query\": \"" + query.replace("\"", "\\\"") + "\", \"max_results\": 5}";

            connection.setDoOutput(true);
            connection.getOutputStream().write(requestBody.getBytes());

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return "Tavily API error: HTTP " + responseCode;
            }

            String result = new BufferedReader(new InputStreamReader(connection.getInputStream()))
                    .lines()
                    .collect(Collectors.joining("\n"));

            // Return raw Tavily JSON for the LLM to parse tool results from
            return "TAVILY_RESULTS:" + result;
        } catch (Exception e) {
            return "Error searching via Tavily: " + e.getMessage();
        }
    }

    private String searchViaHttp(String query) {
        // Simple DuckDuckGo-lite approach using HTML scraping
        try {
            String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
            HttpURLConnection connection = (HttpURLConnection) new URL("https://html.duckduckgo.com/html/?q=" + encodedQuery).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);

            String html = new BufferedReader(new InputStreamReader(connection.getInputStream()))
                    .lines()
                    .collect(Collectors.joining("\n"));

            // Extract results from DDG HTML response (simplified parsing)
            StringBuilder results = new StringBuilder();
            int idx = 0;
            while ((idx = html.indexOf("<a rel=\"nofollow\" class=\"result__a\"", idx)) != -1 && idx < html.length() - 40) {
                idx += 38; // length of "<a rel=\"nofollow\" class=\"result__a\""
                int linkEnd = html.indexOf(">", idx);
                String linkText = html.substring(idx, Math.min(linkEnd + 50, html.length()));

                // Extract URL from href attribute
                int hrefStart = html.indexOf("\"http", idx - 100);
                if (hrefStart != -1) {
                    int hrefEnd = html.indexOf("\"", hrefStart + 5);
                    String url = html.substring(hrefStart, Math.min(hrefEnd + 50, html.length()));

                    // Extract snippet from <a class=\"result__snippet\">
                    int snippetStart = html.indexOf("<a class=\"result__snippet\"", idx - 200);
                    if (snippetStart != -1) {
                        int snippetContentStart = html.indexOf(">", snippetStart + 30);
                        int snippetEnd = html.indexOf("</", snippetContentStart + 1);
                        String snippet = html.substring(snippetContentStart + 1, Math.min(snippetEnd, html.length()));

                        results.append("Title: ").append(linkText).append("\n");
                        results.append("URL: ").append(url).append("\n");
                        results.append("Description: ").append(snippet).append("\n---\n\n");
                    }
                }
            }

            return results.length() > 0 ? results.toString() : "No search results found for: " + query;
        } catch (Exception e) {
            return "Error searching via DuckDuckGo: " + e.getMessage();
        }
    }
}
