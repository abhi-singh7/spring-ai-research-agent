# Proposal: Replace SearXNG with Self-Hosted Firecrawl as Preferred Search Backend

## Why (Motivation)
The preferred search backend was a local SearXNG instance (`http://localhost:9090`, reached via `WebSearchTool`'s direct HTTP call and a `npx mcp-searxng` stdio MCP connection). A self-hosted Firecrawl API is now running at `http://localhost:3002` and replaces it as the first backend in every routing chain. Firecrawl also exposes a scrape endpoint (`POST /v2/scrape`) that renders pages to markdown — a stronger reader than Jsoup for bot-walled or JS-heavy pages — so it doubles as the URL-reading fallback.

## What Changes

### Added
- **Firecrawl search backend in `WebSearchTool`** — direct HTTP POST to `{app.search.firecrawl-base-url}/v2/search` with body `{"query": ..., "limit": 8}`; response parsed from `{"success": true, "data": {"web": [{url, title, description}]}}` into the standard "N. Title / URL / Snippet" format. `success: false` payloads and non-2xx responses are treated as backend failures so the chain escalates (no auth required for self-hosted).
- **Firecrawl scrape fallback in `UrlReaderTool`** — when Jsoup throws or extracts no readable body, `read_url` escalates to `POST {base}/v2/scrape` with `{"url": ..., "formats": ["markdown"]}` and returns the cleaned markdown (60s read timeout for heavy pages).
- **Config** — `app.search.firecrawl-base-url` (`${FIRECRAWL_BASE_URL:http://localhost:3002}`).

### Modified
- `McpToolRouter` — all chains + `DEFAULT_BACKEND_CHAIN` are now `firecrawl → ddg → ollama_web_search → tavily`.
- `McpClientErrorHandler` — searxng-specific branches renamed to firecrawl; `getNextSearchFallback` aligned with the actual 4-tier chain (firecrawl → ddg → ollama_web_search → tavily → null); availability probe treats ANY HTTP response from port 3002 as "listening" (Firecrawl has no /health endpoint).
- `ChatClientConfig` — startup logs reference firecrawl instead of searxng.
- Tests: `WebSearchToolTest` (stub now serves `/v2/search` with Firecrawl JSON; new test for `success:false` escalation), `McpToolRouterTest`, `McpClientErrorHandlerTest`.
- Docs: `AGENTS.md`, `CLAUDE.md`, `README.md`, `research-agent-backend/docs/ARCHITECTURE.md`, `research-agent-backend/docs/CONFIGURATION.md`.

### Removed
- **SearXNG everywhere** — the `searxng` stdio MCP connection from `application.yml` (`npx -y mcp-searxng`, `SEARXNG_URL: http://localhost:9090`), `searchViaSearxng()`/`parseSearXngJson()` and the SearXNG-specific field extractors in `WebSearchTool`, and the searxng entry in `.github/mcp.json`.

## Impact Assessment
| File | Change Type | Risk Level |
|------|-------------|------------|
| `tool/WebSearchTool.java` | Modified — searxng backend swapped for firecrawl (POST, new JSON shape) | Medium — new network path; guarded by timeouts + unit tests on a stub server |
| `tool/UrlReaderTool.java` | Modified — new scrape fallback after Jsoup failure | Low-Medium — additive; original Jsoup path unchanged when it succeeds |
| `tool/McpToolRouter.java` | Modified — chain names only | Low — pure data change, covered by router tests |
| `service/McpClientErrorHandler.java` + test | Modified — renamed branches, fallback order aligned to real chain | Low — not wired at runtime; tests updated |
| `resources/application.yml` | Modified — removed searxng MCP connection, added `app.search.firecrawl-base-url` | Low — default points at the local Firecrawl instance |
| Docs (`AGENTS.md`, `CLAUDE.md`, `README.md`, backend docs) | Modified — searxng → firecrawl references | Low |

**Behavioral note:** if the Firecrawl server is down, search degrades exactly as before with a dead first backend — the chain escalates to DuckDuckGo within the same tool call and returns a decisive verdict.
