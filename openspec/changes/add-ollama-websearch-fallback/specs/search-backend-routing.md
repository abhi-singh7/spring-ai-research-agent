# Requirements: Search Backend Routing

## ADDED Requirements — Ollama Web Search Third Fallback

### Requirement: Ollama is the final escalation target in every search chain
The routed backend chain SHALL end with `ollama_web_search` for every task type (`latest-information`, `general-search`, `search-fallback`) and for unknown/null/blank types via `DEFAULT_BACKEND_CHAIN`. The full order SHALL be `searxng → ddg → ollama_web_search`.

#### Scenario: Both preferred backends fail
- **WHEN** SearXNG returns no results or an error AND DuckDuckGo is rate-limited/blocked (or errors)
- **THEN** the same tool call invokes Ollama's hosted web search API before returning any verdict

#### Scenario: Earlier backend succeeds
- **WHEN** SearXNG or DuckDuckGo returns usable results
- **AND THEN** Ollama is NOT contacted in that call (escalation stops at first success)

### Requirement: Ollama search executes the hosted API with bounded failure behavior
`WebSearchTool` SHALL call `POST {app.search.ollama-base-url}/api/web_search` with an `Authorization: Bearer` header built from `app.search.ollama-api-key`, a JSON body of `{query, max_results}`, and connect/read timeouts no greater than the other backends'. Responses SHALL be parsed from `{results:[{title,url,content}]}` into the shared numbered "Title / URL / Snippet" format.

#### Scenario: API key not configured
- **WHEN** `app.search.ollama-api-key` is blank/empty
- **AND THEN** the backend reports `OLLAMA_API_KEY not configured` in the verdict WITHOUT making a network call to the hosted API

#### Scenario: Authenticated request rejected or rate-limited
- **WHEN** the hosted API returns non-2xx (e.g. 401 unauthorized, 429 rate limit)
- **AND THEN** the failure reason includes the HTTP status and a bounded error-message snippet, so the verdict is diagnostic

#### Scenario: Empty result set
- **WHEN** the hosted API responds with no results entries
- **AND THEN** the backend reports `returned no results` and the call ends with the all-backends-exhausted verdict (no LLM retry loop)

### Requirement: Configuration must not break startup when the key is absent
The new properties SHALL resolve safely without an `OLLAMA_API_KEY` environment variable (`app.search.ollama-api-key` uses an empty default), and application context creation SHALL NOT fail on its absence.

## MODIFIED Requirements — Search escalation chain composition

### Requirement: Routed chains are three-tier
`McpToolRouter.resolveBackends(taskType)` SHALL return a non-empty, order-preserving chain whose first two elements remain `searxng`, `ddg` (preferred → independent), with the third element `ollama_web_search`.
