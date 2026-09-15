# Tasks: Replace SearXNG with Self-Hosted Firecrawl

## Implementation
- [x] `application.yml` — remove the `searxng` stdio MCP connection (`npx -y mcp-searxng`, `SEARXNG_URL: http://localhost:9090`); add `app.search.firecrawl-base-url` (`${FIRECRAWL_BASE_URL:http://localhost:3002}`)
- [x] `McpToolRouter` — `DEFAULT_BACKEND_CHAIN` + all three task-type chains → `firecrawl → ddg → ollama_web_search → tavily`; comments updated
- [x] `WebSearchTool` — replace `searchViaSearxng()`/`parseSearXngJson()` with `searchViaFirecrawl()` (POST `/v2/search`, no auth) + `parseFirecrawlJson()` (`success` flag, `data.web[]` with title/url/description); remove SearXNG-only field extractors; switch case `"firecrawl"`; inject `app.search.firecrawl-base-url`
- [x] `UrlReaderTool` — additive Firecrawl scrape fallback: Jsoup failure/empty body → POST `/v2/scrape` `{"url", "formats":["markdown"]}` (60s read timeout) → cleaned markdown, else previous outcome/error
- [x] `McpClientErrorHandler` — searxng branches → firecrawl; `getNextSearchFallback` aligned to the real chain (firecrawl → ddg → ollama_web_search → tavily → null); availability probe: any HTTP response on port 3002 = listening (no /health endpoint)
- [x] `ChatClientConfig` — startup logs reference firecrawl instead of searxng
- [x] `.github/mcp.json` — remove dead searxng entry
- [x] Docs — `AGENTS.md`, `CLAUDE.md`, `README.md`, `research-agent-backend/docs/ARCHITECTURE.md`, `research-agent-backend/docs/CONFIGURATION.md` (MCP tables, routing chains, env-var reference incl. `FIRECRAWL_BASE_URL`)

## Verification
- [x] `WebSearchToolTest` (9 tests) — stub serves `/v2/search` with Firecrawl JSON: success skips ddg; empty `data.web` escalates; new `success:false` test escalates in the same call; 500 + all-fail verdict names `firecrawl`; dead-port simulation still reaches ddg
- [x] `McpToolRouterTest` (12 tests) — exact firecrawl-first order for every task type + default chain
- [x] `McpClientErrorHandlerTest` (16 tests) — renamed server expectations; fallback order assertions (ddg → ollama_web_search, ollama_web_search → tavily, tavily → null)
- [ ] End-to-end against the live Firecrawl instance at localhost:3002 (requires the running app + local LLM) — e.g. `curl -s http://localhost:8080/api/research/history` after a research run to confirm firecrawl-sourced references
