## Why

Research agent returns poor or irrelevant results when investigating topics it has no prior knowledge of because the web search pipeline is broken: SearXNG queries return raw HTML instead of JSON, MCP tools are silently dropped when unavailable, and many target pages (paywalled/JS-rendered) return empty content for Jsoup. This means the LLM synthesizes reports from weak or missing data — indistinguishable from hallucination to users.

## What Changes

- **SearXNG queries use JSON format** (`?format=json`) so search results are structured with titles, URLs, and snippets that the LLM can reliably parse for tool calling
- **Proper MCP tool fallback chain**: when MCP servers fail (ollama_web_search.py, duckduckgo-mcp-server), gracefully degrade to local Java tools instead of silently dropping all search capability
- **DuckDuckGo HTML fallback reads full response** and extracts URLs via Jsoup selectors (`a.url_header`) for reliable URL extraction from raw search results
- **Better content extraction**: when Jsoup can't find article content in a page, fall back to reading the full body text instead of returning empty results

## Capabilities

### New Capabilities

- `web-search-pipeline`: Structured web search with JSON-format SearXNG responses, robust MCP fallback chain, and reliable URL extraction from both JSON and HTML search result formats
- `content-extraction-fallback`: Multi-strategy page content extraction — Jsoup article selectors → full body text → LLM summarization of snippets when full content is unavailable

### Modified Capabilities

<!-- None — no existing spec-level requirements to modify -->

## Impact

- **Modified**: `WebSearchTool.java` — add JSON format parameter for SearXNG, improve DuckDuckGo HTML parsing
- **Modified**: `UrlReaderTool.java` — add fallback body text extraction when article selectors fail
- **Modified**: `McpClientErrorHandler.java` — implement proper MCP server availability checking at startup time
- **New**: Spring AI tool callback verification on startup to confirm MCP tools are registered
- **Dependencies**: No new external dependencies required (SearXNG already configured)
