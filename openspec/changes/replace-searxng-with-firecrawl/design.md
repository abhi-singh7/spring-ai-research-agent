# Design: Replace SearXNG with Self-Hosted Firecrawl

## API Contract (verified against the running self-hosted instance)

Firecrawl v2 surface at `http://localhost:3002`:

| Endpoint | Method | Body | Response (fields we use) |
|----------|--------|------|---------------------------|
| `/v2/search` | POST | `{"query": "...", "limit": N}` | `{"success": true, "data": {"web": [{"url", "title", "description"}, ...]}}` |
| `/v2/scrape` | POST | `{"url": "...", "formats": ["markdown"]}` | `{"success": true, "data": {"markdown": "...", "metadata": {...}}}` |

- There is **no `/health` endpoint** — any HTTP response (even 404) proves the server is listening.
- Search supports optional `scrapeOptions` to inline page content per result; we do NOT use it — content is read separately via `read_url`, keeping search fast and letting the LLM choose which URLs to read.
- Scrape can be slow on heavy pages (≈60s) — hence the 60s read timeout for that one call, versus the usual 10s.

## Technical Approach

### 1. `WebSearchTool` — swap backend #1

The tool already executes the whole chain in ONE call with bounded timeouts and a decisive verdict; only the first backend's transport/shape changes:

- `searchViaSearxng()` (GET `{base}/search?q=...&format=json`) → `searchViaFirecrawl()` (POST `{base}/v2/search`, JSON body, no auth header).
- New parser `parseFirecrawlJson()`: checks `success` flag first (`false` → fail with the API's `error` text), then reads `data.web[]`; fields `title`/`url`/`description` (with lenient fallbacks to `content`/`snippet`). Empty array → "returned no results" so escalation proceeds.
- Reuses existing `openJsonPost()` / `readHttpError()` / `read()` helpers — non-2xx surfaces a bounded error snippet in the failure verdict, same as Ollama/Tavily.
- SearXNG-specific code removed: `parseSearXngJson()`, `extractTitle/Url/Snippet` helpers (only SearXNG used them).

### 2. `McpToolRouter` — chain names only

All three task-type chains and `DEFAULT_BACKEND_CHAIN`: `firecrawl → ddg → ollama_web_search → tavily`. No structural change; the router is pure data.

### 3. `UrlReaderTool` — additive scrape fallback

Ordering keeps Jsoup first (local, free, fast); Firecrawl only runs when Jsoup throws or yields no readable body:

```
Jsoup OK + content        → return cleaned text (unchanged behavior)
Jsoup OK + empty content  → Firecrawl /v2/scrape → markdown? return it : ""
Jsoup threw               → Firecrawl /v2/scrape → markdown? return it : original error message
```

Markdown is normalized with the existing `cleanContent()` (whitespace collapse, 15k cap) and truncated to the caller's `maxContentLength`. Scrape failures degrade silently to the previous outcome — the tool never fails harder than before.

### 4. `McpClientErrorHandler` — consistency pass

Not wired at runtime (only its own tests reference it), but kept consistent: searxng branches → firecrawl; `getNextSearchFallback` now mirrors the real chain (`firecrawl → ddg → ollama_web_search → tavily → null`); availability probe hits port 3002 and treats ANY response as "listening" (no /health endpoint).

### 5. Config

```yaml
app:
  search:
    firecrawl-base-url: ${FIRECRAWL_BASE_URL:http://localhost:3002}
```

Injected into `WebSearchTool` (search) and `UrlReaderTool` (scrape) via `@Value`. The searxng stdio MCP connection is removed from `application.yml`; Firecrawl needs no MCP wiring because it is a plain HTTP API.

## Failure Modes

| Situation | Behavior |
|-----------|----------|
| Firecrawl down (connection refused) | Chain escalates to ddg in the same call; verdict lists `firecrawl: ConnectException...` |
| Firecrawl returns `success:false` | Treated as failure with the API's error text; chain escalates |
| Firecrawl returns empty `data.web` | "returned no results"; chain escalates |
| Jsoup fails + Firecrawl scrape fails | Original Jsoup error message returned (no behavior regression) |

## Testing Strategy

- `WebSearchToolTest` — stub server now serves `/v2/search` with the Firecrawl JSON shape; existing escalation matrix preserved (success skips ddg; empty escalates; 500 escalates; all-fail verdict names `firecrawl`; dead-port simulation). New test: `success:false` payload escalates to DuckDuckGo.
- `McpToolRouterTest` — exact chain order assertions updated to firecrawl-first.
- `McpClientErrorHandlerTest` — renamed server expectations + new fallback-order assertions (ddg → ollama_web_search, ollama_web_search → tavily, tavily → null).
