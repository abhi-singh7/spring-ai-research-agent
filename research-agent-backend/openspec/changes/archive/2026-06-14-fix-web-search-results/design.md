## Context

Research agent uses Spring AI tool calling to enable an LLM to perform web research. The search pipeline is:

```
LLM decides to use "search" tool → WebSearchTool.search(query, taskType)
    → classify(taskType) → getPreferredServer(taskType)
    → if MCP server available: route via Spring AI MCP client (mcp-web_search-search)
    → if MCP unavailable: fall through to local Java HTTP calls
      
Local Java fallback chain (priority by task type):
  latest-information: web_search (ollama_web_search.py MCP) → searxng → ddg_search
  general-search:     searxng → ddg_search → web_search
  search-fallback:    ddg_search → searxng → web_search

SearXNG local endpoint: http://localhost:9090/search?q=QUERY (NO JSON format)
DuckDuckGo fallback:    https://html.duckduckgo.com/html/?q=QUERY
```

Currently the pipeline is broken for unknown topics because SearXNG returns raw HTML without JSON format, MCP servers are silently dropped when unavailable, and many target pages return empty content for Jsoup.

## Goals / Non-Goals

**Goals:**
- Search results are structured (JSON) so LLM can reliably extract URLs for tool calling
- All search capability is preserved even when MCP servers fail
- URL extraction from both JSON and HTML search result formats works reliably
- Page content extraction falls back to body text when article selectors fail

**Non-Goals:**
- Replacing the SearXNG MCP server or ollama_web_search.py MCP server with new MCP servers
- Adding Tavily API support (already exists but unused)
- Changing the Spring AI MCP client architecture
- Implementing caching, rate limiting, or result deduplication

## Decisions

### 1. SearXNG queries MUST use JSON format (`?format=json`)

**Decision**: Add `&format=json` parameter to all SearXNG HTTP calls.

**Rationale**: The current code makes raw HTTP GET requests to SearXNG without specifying a response format, so it defaults to HTML (200+ lines of unstructured markup). JSON format returns structured results with titles, URLs, snippets, and engines — the LLM can parse these reliably for URL extraction.

**Alternatives considered**:
- Jsoup-based URL extraction from HTML: fragile, breaks on SearXNG layout changes, no snippet metadata for LLM to use
- Switching to a different search engine entirely: unnecessary — SearXNG works well with JSON format

### 2. DuckDuckGo fallback reads full response (not limited to 200 lines)

**Decision**: Remove the `.limit(200)` on the DuckDuckGo HTML reader, or increase it significantly. Also extract URLs using Jsoup selectors (`a.url_header`).

**Rationale**: The current `read()` method limits output to 200 lines, which truncates search results before they're complete. For DuckDuckGo's HTML format, Jsoup can reliably extract article links via the `.url_header` class selector.

**Alternatives considered**:
- Use DuckDuckGo's unofficial API: would add external dependency and break point
- Parse HTML with regex: fragile and error-prone — Jsoup is better

### 3. MCP server availability check at startup time

**Decision**: Implement proper startup-time health checks for each MCP server connection, logging which tools are available vs unavailable. Also verify that Spring AI registered the MCP tools by attempting a simple tool call during validation.

**Rationale**: Currently there's no way to know if MCP servers failed silently. The `McpClientErrorHandler.isMcpServerAvailable()` method exists but is only used for display — it doesn't prevent broken state from propagating. A startup health check gives immediate visibility into what tools are actually available.

**Alternatives considered**:
- Runtime retry on tool call failure: harder to debug, slower UX
- Require all MCP servers to be running (hard fail): too strict for development environments

### 4. Content extraction fallback chain: article selector → body text → empty content error

**Decision**: In `UrlReaderTool.extractMainContent()`, when Jsoup finds no article selectors match the page structure, fall back to extracting raw body text instead of returning "No readable content found."

**Rationale**: Many pages (ScienceDirect paywalled articles, MDPI JS-rendered pages) have empty or minimal `<body>` text that Jsoup sees. The current code returns an error when no article selectors match, giving the LLM zero content to work with. Returning whatever body text exists is better than nothing — the LLM can still synthesize from snippets.

**Alternatives considered**:
- Skip unreadable pages entirely and tell the LLM: adds complexity but might be cleaner
- Return raw HTML for the LLM to process: too noisy, exceeds context limits

## Risks / Trade-offs

### [Risk] JSON format may not include all engines SearXNG has configured → fewer results than expected
**Mitigation**: Request `&format=json` with no engine restrictions — SearXNG returns all enabled engines by default. Monitor result count and increase if needed.

### [Risk] Increased response size from removing 200-line limit on DuckDuckGo HTML
**Mitigation**: The limit was arbitrary — actual search results are typically well under a few KB. No performance concern. If needed, add an explicit line limit (e.g., 1000 lines).

### [Risk] MCP tool name collision after adding `mcp-` prefix to MCP tools
**Rationale assessment**: The `mcp-` prefix is already in use via `spring.ai.mcp.client.tool-name-prefix:mcp-`. This is intentional and documented. Smaller models may struggle with the prefixed names — this is a known limitation of gemma-4-e4b, not introduced by this change.

### [Risk] Body text fallback returns too much noise (navigation, ads)
**Mitigation**: The existing `cleanContent()` method already normalizes whitespace and removes control characters. If body text extraction becomes an issue, add a minimum content length threshold before returning it.

## Migration Plan

No migration needed — this is all code changes with no database or API contract changes. Deploy as-is:
1. Build and restart backend (MCP server health checks run at startup)
2. Verify logs show correct MCP tool availability status
3. Test search for a known-unknown topic to confirm JSON results are returned

## Open Questions

- Should we increase the default `max_results` from 3 to 5-10? Currently both ollama_web_search.py and Tavily use `max_results=3`, which is insufficient for niche topics.
- Is the `OLLAMA_API_KEY` environment variable actually set on this system? The MCP server requires it but the config uses `${OLLAMA_API_KEY}` with no default — if unset, the MCP server silently fails to authenticate with Ollama's hosted search API.
