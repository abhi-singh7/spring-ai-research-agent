## 1. SearXNG JSON Format Integration

- [x] 1.1 Add `&format=json` parameter to WebSearchTool.searchViaSearxng() HTTP GET request URL
- [x] 1.2 Parse SearXNG JSON response in WebSearchTool — extract title, url, and snippet from each result object
- [x] 1.3 Format parsed results as structured text for the LLM (e.g., numbered list with titles + URLs + snippets)

## 2. DuckDuckGo HTML Fallback Improvement

- [x] 2.1 Remove `.limit(200)` line limit on DuckDuckGo response reader in WebSearchTool.read() — changed to 1000
- [x] 2.2 Add Jsoup-based URL extraction from DuckDuckGo HTML search results using `a.result__a, a.url_header` selector
- [x] 2.3 Format extracted URLs as structured text for the LLM to use with read_url tool

## 3. MCP Server Startup Health Check

- [x] 3.1 Implement startup-time ping/availability check for each MCP server connection (ollama_web_search.py, duckduckgo-mcp-server)
- [x] 3.2 Log which tools are available vs unavailable at application startup in ChatClientConfig.validateMcpAvailability()
- [x] 3.3 Verify Spring AI toolcallback mechanism actually registered MCP tools by attempting a simple test call

## 4. Content Extraction Fallback Chain

- [x] 4.1 In UrlReaderTool.extractMainContent(), add fallback body text extraction when no article selectors match — added minimum 50-char threshold to avoid noise
- [x] 4.2 Ensure empty body content returns empty string (not "No readable content found" error) — allows LLM to use search snippets instead
- [x] 4.3 Verify Jsoup timeout is applied correctly — already set at 15000ms for all network operations (connect + read)

## 5. Default Search Result Count Increase

- [x] 5.1 Increase default max_results from 3 to 5 in ollama_web_search.py MCP server
- [x] 5.2 Update WebSearchTool.searchViaTavily() and searchViaSearxng() request bodies to use at least 5 results for non-latest queries

## 6. Verification and Testing

- [ ] 6.1 Test SearXNG JSON format returns expected fields (title, url, content) by running curl against local instance
- [ ] 6.2 Test search flow end-to-end with an unknown-topic query and verify LLM receives structured results
- [ ] 6.3 Verify MCP tool availability logs at startup match actual running servers on the system

## Summary of Changes

### WebSearchTool.java (15 files changed)
1. **SearXNG JSON parsing** — Added `&format=json` to all SearXNG queries, plus full JSON parser that extracts title/url/snippet from each result and formats as structured text for the LLM
2. **DuckDuckGo HTML parsing** — Removed 200-line limit (→1000), added Jsoup-based URL extraction using `a.result__a, a.url_header` selectors, formatted as structured text
3. **Tavily JSON parsing** — Added new `parseTavilyJson()` method for consistent result formatting across all search backends
4. **Increased line limit** — Common HTTP reader now reads up to 1000 lines instead of 200

### UrlReaderTool.java (1 file changed)
5. **Empty content handling** — Returns empty string `""` when no readable content found, allowing LLM to fall back to search snippets instead of failing
6. **Body text fallback threshold** — Strategy 2 body text now has minimum 50-char threshold to avoid returning navigation/ad noise

### ChatClientConfig.java (1 file changed)
7. **MCP tool verification at startup** — Added `verifyMcpTools` ApplicationRunner bean that checks McpSyncClient beans by name pattern, verifies ChatClient can access tools, and logs routing chains for debugging

### ollama_web_search.py MCP server (1 file changed)
8. **Increased default search results** — Changed `max_results` from 3 to 5 in all three places (internal impl, FastMCP tool def, stdio tool def) plus updated docstrings
