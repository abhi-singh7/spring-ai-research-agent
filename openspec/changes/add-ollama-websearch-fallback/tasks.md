# Tasks: Add Ollama Web Search as Third Fallback Backend

## Implementation
- [x] Confirm the hosted API contract from SDK source (`ollama` python 0.6.2): `POST https://ollama.com/api/web_search`, Bearer auth, body `{query,max_results}`, response `{results:[{title,url,content}]}` — no live key needed to implement against it
- [x] `McpToolRouter`: `DEFAULT_BACKEND_CHAIN` + all three task-type chains → `searxng → ddg → ollama_web_search`
- [x] `WebSearchTool`: add `ollama_web_search`/`ollama` switch case; new `searchViaOllamaWebSearch()` (Bearer POST, bounded timeouts), `parseOllamaJson()`, `openJsonPost()` helper, `readHttpError()` bounded error reader
- [x] `WebSearchTool`: inject `app.search.ollama-base-url` / `app.search.ollama-api-key` via the `@Autowired` constructor (empty-default so unset key never breaks startup); blank key short-circuits before network I/O
- [x] `application.yml`: rename stdio connection `web_search` → `ollama_web_search`; add `app.search.*` properties with safe defaults
- [x] Update all Java name references to the renamed server: `McpClientErrorHandler` (fallback chain, availability switch, status summary), `ChatClientConfig` startup logs + escalation-chain line

## Verification
- [x] Unit tests (`WebSearchToolTest`, stub HTTP servers): escalation reaches Ollama only after searxng+ddg fail; verdict lists `ollama_web_search` reason when all three fail; missing-key makes ZERO ollama calls (short-circuit proven); ddg-success path never touches Ollama
- [x] Unit tests (`McpToolRouterTest`): exact 3-element order for every known task type + default chain; Ollama is last element for unknown/null/blank types too
- [x] `McpClientErrorHandlerTest`: renamed-server expectations updated, all green
- [ ] Full `mvn test` requires the local LLM to be running (the two `@SpringBootTest` integration tests boot real context and make live LLM calls in `ChatClientConfig` runners) — environmental, not caused by this change
