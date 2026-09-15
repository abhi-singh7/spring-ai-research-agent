# AGENTS.md — Research Agent

## Architecture

Full-stack research tool: user submits a topic → LLM breaks it into sub-topics → searches the web with MCP/local tools → reads content from multiple sources → synthesizes findings → displays via SSE streaming.

- **Backend**: `research-agent-backend/` — Java 21 + Spring Boot 3.5.4 + Spring AI 1.1.7 (OpenAI-compatible local LLM)
- **Frontend**: `research-agent-ui/` — Angular 18+ with Signals/RxJS + Angular Material M3
- **Database**: PostgreSQL

## Running

### Backend (`research-agent-backend/`)
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn spring-boot:run          # port 8080 default
curl http://localhost:8080/api/research/history   # verify
```

Requires PostgreSQL and a local LLM endpoint — configured in `src/main/resources/application.yml`. Default model: `google/gemma-4-26b-a4b-qat` (commented alternative: `qwopus3.6-35b-a3b-v1`).

### Frontend (`research-agent-ui/`)
```bash
npm start                     # proxy.conf.json forwards /api → localhost:8080
# or: npx ng serve --proxy-config proxy.conf.json
```

## Key Implementation Gotchas

- **Spring AI 1.1.7** uses `spring-ai-starter-model-openai` artifact (NOT `spring-ai-openai-spring-boot-starter`). Config classes are auto-configured — no bean definitions needed. See ChatClientConfig.java.
- **Search backend routing**: Search backends are executed by `WebSearchTool` over HTTP in ONE tool call: `firecrawl → ddg → ollama_web_search → tavily` (see `McpToolRouter.resolveBackends`). Firecrawl is a self-hosted API (`app.search.firecrawl-base-url`, default `http://localhost:3002`) — NOT an MCP server. Two stdio MCP servers (`ollama_web_search`, `ddg_search`) are additionally auto-configured in application.yml; `UrlReaderTool` escalates to Firecrawl's `/v2/scrape` when Jsoup can't read a page.
- **Angular standalone components**: All `@Component` decorators must include `standalone: true` when using the `imports` property.
- **Angular Material M3 theming**: Use `mat.define-theme((color: ()))`. The `all-component-themes($theme)` mixin must be wrapped in a CSS selector — cannot be called at root level. See styles.scss.
- **SSE streaming resilience**: Backend uses `Flux<String>` via `.stream().content()`; frontend detects connection loss with exponential backoff reconnection (3 attempts) and falls back to polling on failure. A 90-second stall timer forces completion detection if no chunks arrive during report generation.
- **Iterative research + provenance**: Each sub-topic runs up to N LLM rounds (request `maxIterations`, else `app.research.default-max-iterations`: 3) — round 1 sweeps planned search queries, later rounds chase Open Questions. Early stop on coverage (≥3 captured source URLs AND ≥400 chars findings) or when a round adds no new URLs (round ≥2). References are built ONLY from "Title — URL" lines parsed out of research notes (deduped by normalized URL); the synthesis prompt forbids citing uncaptured URLs. Per-sub-topic failures become FAILED steps and continue; only total failure fails the session. Runs on an async pool thread with **no** OSIV/Hibernate session — load via `findByIdWithSteps` (fetch-join) so lazy `steps` mutations are safe outside a transaction; never rely on ambient sessions in this flow. Step ids are DB-generated, so always **adopt the instance returned by `repo.save()`** (`session = sessionRepo.save(session)`), or already-persisted steps re-insert with duplicate keys on later merges — covered by `OrchestratorPersistenceIntegrationTest`.
- **Abandoned session cleanup**: Background scheduler (`@Scheduled`) marks PROCESSING sessions stuck >`app.cleanup.stale-after` (default `PT15M`) as CANCELLED every `app.cleanup.interval` (default `PT20M`). Configurable via `app.cleanup.stale-after` and `app.cleanup.interval`.
- **PostgreSQL DDL-auto: `validate`** — no automatic schema generation. Changes require manual migration or disabling validate mode in dev. `hibernate.jdbc.lob.non_contextual_creation=true` is required to avoid PostgreSQL LOB API errors.

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
| `src/main/resources/application.yml` | MCP connections, LLM model, datasource, SSE timeout (600s), cleanup scheduler config |
| `src/main/java/com/researchagent/service/ResearchOrchestratorService.java` | Core pipeline (see below): breakdown w/ planned search queries → iterative rounds per sub-topic (retries, partial-failure tolerance) → streamed synthesis with References built from captured URLs |
| `src/main/java/com/researchagent/service/LlmGateway.java`, `SpringAiLlmGateway.java` | LLM seam: `complete()`/`streamComplete()` with per-request temperature; production wraps the tools-configured ChatClient, tests mock the interface |
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
