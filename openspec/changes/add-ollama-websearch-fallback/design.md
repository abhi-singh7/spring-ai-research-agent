# Design: Ollama Web Search Third Fallback

## Context
The search escalation that actually executes tool calls lives in the local `WebSearchTool` (not in the MCP stdio clients): `McpToolRouter.resolveBackends(taskType)` returns an ordered backend list, and `WebSearchTool.search()` walks it in one call, returning a decisive verdict. The three MCP servers in `application.yml` (`searxng`, `ddg_search`, `ollama_web_search`) are LLM-visible via auto-configured tool callbacks; the local tool is what guarantees "one call → results or explicit exhaustion".

## Decisions

### Execute Ollama search as a direct HTTP backend, not via MCP stdio
The python server (`ollama_web_search.py`) wraps `ollama.Client.web_search()`, which (SDK 0.6.2 source) POSTs to `https://ollama.com/api/web_search` with `Authorization: Bearer <OLLAMA_API_KEY>` and body `{query, max_results}`, returning `{results:[{title,url,content}]}`. Calling that endpoint directly from Java:
- matches the established pattern (SearXNG = direct JSON GET; DuckDuckGo = direct HTML GET);
- avoids spawning/depending on the uv/python stdio process inside a hot escalation path;
- stays fully unit-testable against `com.sun.net.httpserver` stubs.
The stdio MCP server remains configured for LLM-visible use (as searxng/ddg already are).

### Naming: backend id = `ollama_web_search`
Router/tool switch ids follow the existing lowercase style (`searxng`, `ddg`). The yml key was renamed from `web_search` to `ollama_web_search` so the "third MCP option" is identifiable; all Java name references (`McpClientErrorHandler`, `ChatClientConfig` logs) were updated in lockstep. Aliases accepted by the tool switch: `ollama`.

### Config surface
- `app.search.ollama-base-url` (default `https://ollama.com`) — testable/overridable.
- `app.search.ollama-api-key` (`${OLLAMA_API_KEY:}`) — **empty default**, so an unset key degrades to a runtime reason string instead of failing startup placeholder resolution; the stdio env entry keeps its existing `${OLLAMA_API_KEY}` (unchanged behavior).

### Failure semantics
- Key blank → `fail("OLLAMA_API_KEY not configured")` BEFORE any network I/O (asserted in tests: zero hits on the stub server).
- Non-2xx → `fail("HTTP <status>: <bounded error-stream>")` so 401/429 reasons reach the verdict.
- `{results: []}` / no results field → `fail("returned no results")`.
- Unparseable body → same raw-fallback heuristic as SearXNG (`looksLikeResults`).

## Test Strategy
All on in-process stub HTTP servers (no network, no key needed): escalation reaches Ollama only after searxng+ddg fail; verdict lists `ollama_web_search` per-backend reason when all three fail; missing-key short-circuit makes zero ollama calls. Router tests pin the exact 3-element order for every task type + default chain.
