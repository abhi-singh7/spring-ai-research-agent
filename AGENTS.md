# AGENTS.md — Research Agent

## Architecture

Full-stack research tool: user submits a topic → LLM breaks it into sub-topics → searches the web with MCP/local tools → reads content from multiple sources → synthesizes findings → displays via SSE streaming.

- **Backend**: `research-agent-backend/` — Java 21 + Spring Boot 4.1.1 + Spring AI 2.0.1 (OpenAI-compatible local LLM)
- **Frontend**: `research-agent-ui/` — Angular 18+ with Signals/RxJS + Angular Material M3
- **Database**: MongoDB — single `research_session` collection with steps embedded in each session document (migrated from PostgreSQL; legacy PG schema retained as a rollback path)

## Running

### Backend (`research-agent-backend/`)
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn spring-boot:run          # port 8080 default
curl http://localhost:8080/api/health             # unauthenticated liveness probe (/api/research/** needs a JWT)
```

Requires MongoDB (`spring.mongodb.uri`, default `mongodb://localhost:27017/research-agent`) and a local LLM endpoint — configured in `src/main/resources/application.yml`. Default model: `google/gemma-4-26b-a4b-qat` (commented alternative: `qwopus3.6-35b-a3b-v1`).

### Frontend (`research-agent-ui/`)
```bash
npm start                     # proxy.conf.json forwards /api → localhost:8080
# or: npx ng serve --proxy-config proxy.conf.json
```

### Deployment (self-hosted runner on the local Ubuntu machine)
- A self-hosted Actions runner (`abhi-ubuntu`, labels `self-hosted, linux, ubuntu-local`) runs from `~/actions-runner` as the user systemd service `actions-runner`.
- `.github/workflows/deploy.yml` fires after "Research Agent CI" succeeds on `main` (also manually via workflow dispatch): builds frontend + backend jar in the runner workspace, installs to `/home/abhi/research-agent-deploy/app.jar`, and restarts the user systemd service `research-agent` (API + UI on :8080).
- Local management: `systemctl --user status|restart research-agent` · runner: `systemctl --user status actions-runner`.
- Full line-by-line explanation of both workflows, the runner setup, and day-2 operations commands: `docs/ci-cd-deployment.md`.

## Key Implementation Gotchas

- **Spring AI 2.0 OpenAI client timeouts**: the 2.0 rewrite uses the official `openai-java` SDK, whose auto-config defaults `spring.ai.openai.timeout` to **60s** (call/read/write). Long local-LLM calls (final report generation) die at exactly 60s with `OpenAIIoException: Stream failed` / `InterruptedIOException: timeout`. Set `spring.ai.openai.timeout: PT10M` in application.yml, kept in sync with `spring.ai.sse.timeout`.
- **Spring AI 2.0** uses `spring-ai-starter-model-openai` artifact (NOT `spring-ai-openai-spring-boot-starter`). Config classes are auto-configured — no bean definitions needed. See ChatClientConfig.java.
- **SPA served from the backend jar (JWT)**: the deployed UI is the Angular build copied into `static/` at deploy time, and Spring Security protects **only `/api/**`** — `index.html`, JS/CSS bundles and client-side routes must stay public, because the browser cannot send a Bearer header on page/asset loads; protecting them 401-walls the entire UI including `/login`. Unauthenticated API calls get 401 → the Angular app redirects to login. Deep links/refreshes are forwarded to `index.html` by `SpaForwardController` (extension-less SPA routes only — never assets, never `/api/**`; keep its route list in sync with `app.routes.ts`).
- **Search backend routing**: Search backends are executed by `WebSearchTool` over HTTP in ONE tool call: `firecrawl → ddg → ollama_web_search → tavily` (see `McpToolRouter.resolveBackends`). Firecrawl is a self-hosted API (`app.search.firecrawl-base-url`, default `http://localhost:3002`) — NOT an MCP server. Two stdio MCP servers (`ollama_web_search`, `ddg_search`) are additionally auto-configured in application.yml; `UrlReaderTool` escalates to Firecrawl's `/v2/scrape` when Jsoup can't read a page.
- **Angular standalone components**: All `@Component` decorators must include `standalone: true` when using the `imports` property.
- **Angular 20 `viewChild` on component elements**: a string-selector `viewChild<T>('ref')` where `#ref` sits on a *component* element returns the **component instance**, not an `ElementRef` — the type argument (`ElementRef<…>`) is cosmetic and `.nativeElement` is undefined. To get the DOM node, put the template ref on a plain `<div>` wrapper around the component (see `report-viewer`'s `#mdHost`).
- **Angular Material M3 theming**: Use `mat.define-theme((color: ()))`. The `all-component-themes($theme)` mixin must be wrapped in a CSS selector — cannot be called at root level. See styles.scss.
- **SSE streaming resilience**: Backend uses `Flux<String>` via `.stream().content()`; frontend detects connection loss with exponential backoff reconnection (3 attempts) and falls back to polling on failure. A 90-second stall timer forces completion detection if no chunks arrive during report generation.
- **Structured LLM output (Spring AI 2.0)**: The breakdown plan (`List<SubTopic>`) and research-round notes (`ResearchRoundNote`) are obtained via `LlmGateway.completeStructured(...)` → `ChatClient.call().entity(...)` — prompt-based JSON schema generation (`BeanOutputConverter`), deliberately NOT `useProviderStructuredOutput()` (local Ollama OpenAI-compatible endpoint; native structured output unreliable there). Round retries keep the 3-attempt budget: attempt 1 structured, attempts 2–3 free-form fallback with legacy text parsing; structured notes are rendered back to the classic markdown shape (`renderRoundNote`) so step content/UI/synthesis input are unchanged. See `openspec/changes/use-spring-ai-structured-output/`.
- **Iterative research + provenance**: Each sub-topic runs up to N LLM rounds (request `maxIterations`, else `app.research.default-max-iterations`: 3) — round 1 sweeps planned search queries, later rounds chase Open Questions. Early stop on coverage (≥3 captured source URLs AND ≥400 chars findings) or when a round adds no new URLs (round ≥2). References are built ONLY from "Title — URL" lines parsed out of research notes (deduped by normalized URL); the synthesis prompt forbids citing uncaptured URLs. Per-sub-topic failures become FAILED steps and continue; only total failure fails the session. Runs on an async pool thread outside any transaction. Steps are embedded in the session document, so there is no fetch-join or lazy loading — `findByIdWithSteps` is a plain `findById` alias and every `save()` atomically replaces the whole document. Always **adopt the instance returned by `repo.save()`** (`session = sessionRepo.save(session)`) so a later save never clobbers newer state with a stale copy — covered by `OrchestratorPersistenceIntegrationTest`.
- **Abandoned session cleanup**: Background scheduler (`@Scheduled`) marks PROCESSING sessions stuck >`app.cleanup.stale-after` (default `PT15M`) as CANCELLED every `app.cleanup.interval` (default `PT20M`). Configurable via `app.cleanup.stale-after` and `app.cleanup.interval`.
- **MongoDB storage (migrated from PostgreSQL)**: sessions live in a single `research_session` collection with steps embedded — no schema migrations; collections/indexes are auto-created from entity annotations (`@Document`, `@CompoundIndex`, `@Indexed`). UUIDs use the STANDARD (RFC 4122) BSON representation (`spring.mongodb.representation.uuid: STANDARD`) — keep it in sync with the one-shot migration script `scripts/migrate_pg_to_mongo.py`. The old PG schema (`db/migration/V1__init_research_tables.sql`) is retained untouched as a rollback path.
- **LLM call logging (LoggingAdvisor)**: every LLM request/response pair is persisted to the `llm_logs` Mongo collection by `LoggingAdvisor`, which implements BOTH `CallAdvisor` and `StreamAdvisor` — Spring AI 2.0 routes `.call()` and `.stream()` through separate advisor chains, and the auto-configured `ChatClient.Builder` does NOT auto-pick-up Advisor beans (explicit `.defaultAdvisors(...)` in ChatClientConfig). Order 0 places it INSIDE ToolCallingAdvisor, so each underlying model round of a tool-calling exchange gets its own document. Per-session attribution flows through the advisor context, not ThreadLocal (stream callbacks run on Reactor threads): `LlmGateway` methods take `UUID sessionId` first and `SpringAiLlmGateway` attaches it via `spec.advisors(a -> a.param(LoggingAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))`. Prompts/responses are rendered with explicit tool-call/tool-result markers — Spring AI keeps tool data outside `Message.getText()`, so plain `Prompt.getContents()` makes intermediate tool rounds look blank/identical. Persist failures are swallowed: logging must never break the research pipeline. Per-session history: `LlmLogRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)`.

## Search Backends & MCP Tool Servers

Two stdio-based MCP servers auto-configured via `application.yml`:

| Server | Command | Purpose |
|--------|---------|---------|
| ollama_web_search | `uv run /home/abhi/ollama_web_search.py` | Web search (requires OLLAMA_API_KEY) |
| ddg_search | `uvx duckduckgo-mcp-server[browser]` | DuckDuckGo fallback search |

The preferred search/scrape backends are plain HTTP APIs called directly by the local Java tools (NOT MCP servers):

| Backend | Endpoint | Purpose |
|---------|----------|---------|
| firecrawl search | `POST {app.search.firecrawl-base-url}/v2/search` (default `http://localhost:3002`) | First backend in every McpToolRouter chain — self-hosted Firecrawl API |
| firecrawl scrape | `POST {app.search.firecrawl-base-url}/v2/scrape` with `{"formats":["markdown"]}` | UrlReaderTool fallback when Jsoup fails or finds no readable body |

See McpToolRouter.java for routing logic, WebSearchTool.java / UrlReaderTool.java for execution, and ChatClientConfig.java for tool registration.

## Key Files

### Backend
| File | Purpose |
|------|---------|
| `src/main/java/com/researchagent/config/ChatClientConfig.java` | ChatClient + MCP validation + tool router beans |
| `src/main/resources/application.yml` | MCP connections, LLM model, MongoDB URI, SSE timeout (600s), cleanup scheduler config |
| `src/main/java/com/researchagent/service/ResearchOrchestratorService.java` | Core pipeline (see below): breakdown w/ planned search queries → iterative rounds per sub-topic (retries, partial-failure tolerance) → streamed synthesis with References built from captured URLs |
| `src/main/java/com/researchagent/service/LlmGateway.java`, `SpringAiLlmGateway.java` | LLM seam: `complete()`/`streamComplete()` with per-request temperature + `completeStructured(...)` (`call().entity(...)`) for breakdown plans and round notes; every method takes the session id first so calls are attributable in `llm_logs`; production wraps the tools-configured ChatClient, tests mock the interface |
| `src/main/java/com/researchagent/advisor/LoggingAdvisor.java` | `CallAdvisor` + `StreamAdvisor` that logs every LLM request/response pair to `llm_logs` with per-session attribution; renders tool calls/results explicitly (see gotchas) |
| `src/main/java/com/researchagent/model/entity/LLMLogs.java`, `repository/LlmLogRepository.java` | `llm_logs` collection: one document per model round (prompt, response, model, streaming flag, status, duration), indexed by `sessionId` |
| `src/main/java/com/researchagent/service/ResearchStreamingService.java` | SSE via `ConcurrentHashMap<UUID, SseEmitter>`, events: PROGRESS, CONTENT, REPORT_CHUNK, REPORT_DONE, STEP_COMPLETE, ERROR |
| `src/main/java/com/researchagent/controller/ResearchController.java` | REST endpoints for research lifecycle + history + bulk delete |
| `src/main/java/com/researchagent/tool/McpToolRouter.java` | Routes search tasks to MCP server chains with fallbacks |
| `src/main/java/com/researchagent/service/AbandonedSessionCleanupService.java` | Background scheduler for stale session cleanup |

### Frontend
| File | Purpose |
|------|---------|
| `src/app/app.routes.ts` | Routing (see CLAUDE.md) |
| `src/app/core/services/research.service.ts` | Primary service: REST + SSE with reconnection/polling fallback, signal state management |
| `src/app/core/services/research-history.service.ts` | Paginated history queries and session deletion |
| `src/app/features/active-research/` | Live streaming session view |
| `src/app/features/research-input/` | New research form with quick-start chips |
| `src/app/features/research-history/` | Paginated history with search, single delete (MatDialog), and bulk multi-select delete |
| `src/app/features/history-detail/` | Historical session detail + follow-up |
| `src/app/shared/components/step-list/` | Step progress display |
| `src/app/shared/components/report-viewer/` | Report content renderer |
| `src/app/shared/components/followup-form/` | Inline follow-up question form |

## Conventions

- All frontend components are standalone — no NgModules.
- Angular strict mode enabled (`strictInjectionParameters`, `strictInputAccessModifiers`, `strictTemplates`).
- Backend uses constructor injection (no field-level `@Autowired`).
- Lombok annotations (`@Data`, `@Slf4j`) reduce boilerplate — must have Lombok plugin in IDE for code completion.
- No lint/test/typecheck scripts defined in package.json or pom.xml. Angular karma tests configured but no npm script for them either.
- Changes follow the OpenSpec workflow under `openspec/changes/<name>/`.
