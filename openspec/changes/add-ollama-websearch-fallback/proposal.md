# Proposal: Add Ollama Web Search as Third Fallback Backend

## Why (Motivation)
Search escalation was two-tier (`searxng` → `ddg`). Both tiers fail together in the same failure mode class: SearXNG is a local instance that can be down or its upstream engines rate-limited, and DuckDuckGo's HTML endpoint is bot-blocked/rate-limited (HTTP 403 / empty pages). When both are unavailable — exactly when research runs need search most — every sub-topic got "no results from any backend" and the run degraded silently.

The third MCP server (`ollama_web_search`, `uv run /home/abhi/ollama_web_search.py`) was already configured in `application.yml` but never referenced by the routing chain, so it could not be reached at all. Its underlying API — Ollama's hosted `POST https://ollama.com/api/web_search` (Bearer-authenticated, verified against the `ollama` python SDK 0.6.2 source) — is an independent index with different rate-limit posture than the other two backends.

## What Changes

### Added
- **Third backend in every routing chain** — `McpToolRouter` chains are now `searxng → ddg → ollama_web_search` for all task types and `DEFAULT_BACKEND_CHAIN`.
- **Ollama backend execution in `WebSearchTool`** — direct HTTP POST to `{app.search.ollama-base-url}/api/web_search` with `Authorization: Bearer <OLLAMA_API_KEY>`, bounded timeouts, response parsed from `{results:[{title,url,content}]}` into the same "N. Title / URL / Snippet" format as the other backends. Missing key degrades to an explicit `OLLAMA_API_KEY not configured` reason (no network call). Non-2xx responses surface a bounded error-stream snippet in the failure verdict.
- **Config** — `app.search.ollama-base-url` (default `https://ollama.com`) and `app.search.ollama-api-key` (`${OLLAMA_API_KEY:}`, empty default so startup never fails on an unset key).

### Modified
- `application.yml` — stdio connection renamed `web_search` → `ollama_web_search` for clarity (it is the Ollama one); new `app.search.*` properties.
- `McpClientErrorHandler`, `ChatClientConfig` — server-name references/logs updated to `ollama_web_search`.

### Removed (if any)
None. SSE event protocol unchanged; local search still executes in ONE tool call with a decisive verdict.

## Impact Assessment
| File | Change Type | Risk Level |
|------|-------------|------------|
| `tool/McpToolRouter.java` | Modified — 3-element chains | Low — pure data change, covered by router tests |
| `tool/WebSearchTool.java` | Modified — new backend + POST helpers | Medium — new network path; guarded by timeouts + unit tests on a stub server |
| `resources/application.yml` | Modified — rename + 2 properties | Low — empty-default property cannot break startup |
| `config/ChatClientConfig.java`, `service/McpClientErrorHandler.java` | Modified — log/name strings only | Low |
