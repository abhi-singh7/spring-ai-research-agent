# Team & User Guide — Research Agent

This document is the human-friendly entry point for understanding the Research Agent project. It assumes **no prior knowledge** of the codebase. If you are an AI coding agent working in this repo, use [AGENTS.md](AGENTS.md) and [CLAUDE.md](CLAUDE.md) instead.

---

## 1. What This Project Is

Research Agent is a **full-stack, AI-powered research tool**. A user types in a topic, and the system autonomously:

1. Breaks the topic down into smaller sub-topics,
2. Searches the web for each sub-topic using an LLM with tool-calling,
3. Reads content from multiple sources,
4. Synthesizes all of that into a single markdown report,
5. Shows everything to the user **in real time** as it happens (via Server-Sent Events / SSE).

In short: **topic → research → report, streamed live.**

---

## 2. Tech Stack

| Layer | Technology | Folder |
|-------|------------|--------|
| Backend | Java 21 + Spring Boot 3.5.4 + Spring AI 1.1.7 (OpenAI-compatible local LLM) | `research-agent-backend/` |
| Frontend | Angular 18+ (Signals/RxJS) + Angular Material M3 | `research-agent-ui/` |
| Database | PostgreSQL | — |
| LLM | Ollama (OpenAI-compatible endpoint, e.g. `gemma-4-26b`) | local |
| Search | Self-hosted Firecrawl API + MCP stdio servers (`ollama_web_search`, `ddg_search`) + local fallback tools | local / MCP |

---

## 3. How It Works (The Pipeline)

```
┌─────────────┐   REST + SSE   ┌──────────────────┐   OpenAI HTTP   ┌───────────┐
│  Browser    │ ─────────────► │  Spring Boot     │ ─────────────► │  Ollama   │
│  (port 4200)│ ◄───────────── │  (port 8080)     │                │  LLM      │
└──────┬──────┘   events       └─────────┬────────┘ └─────┬──────┘
       │                                 │ JDBC           │
       │                                 ▼                ▼
       │                          ┌─────────────┐   ┌──────────────┐
       │                          │ PostgreSQL  │   │ Firecrawl    │
       │                          │ (research-   │   │ search+scrape│
       │                          │  agent DB)   │   │ (port 3002)  │
       │                          └─────────────┘   └──────────────┘
       │   MCP stdio
       └──────────────────────────► ollama_web_search · ddg_search
```

### The three research phases

1. **Topic Breakdown** — The LLM parses the user's topic into structured sub-topics (JSON tool-calling).
2. **Sub-topic Research** — For each sub-topic, the LLM calls `search` and `readUrl` tools to gather information from multiple sources.
3. **Report Synthesis** — The LLM combines all findings into a markdown report, streamed chunk-by-chunk to the browser via SSE.

### Search backend routing

Searches are executed by `WebSearchTool` over HTTP inside a single tool call, in this priority order:

```
firecrawl → ddg → ollama_web_search → tavily
```

- **Firecrawl** (`app.search.firecrawl-base-url`, default `http://localhost:3002`) is a self-hosted API — **not** an MCP server. It provides both `/v2/search` and, as a fallback, `/v2/scrape`.
- The two stdio **MCP servers** (`ollama_web_search`, `ddg_search`) are configured in `application.yml` and act as later fallbacks.
- If a key is missing, that backend reports an explicit "not configured" reason instead of making a network call.

---

## 4. Running the Application

### Prerequisites

- **Java 21** (OpenJDK)
- **Node.js 20+** with npm
- **PostgreSQL 15+** — create the database first: `createdb -U postgres research-agent`
- **Ollama** running locally with a model available (e.g. `gemma-4-26b`) at `http://localhost:1234`
- **Firecrawl** self-hosted API at `http://localhost:3002` (optional but recommended for search)

### Backend

```bash
cd research-agent-backend
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn spring-boot:run          # starts on port 8080
curl http://localhost:8080/api/research/history   # verify
```

All configuration lives in `src/main/resources/application.yml`. The most important settings:

| Setting | Default | Purpose |
|---------|---------|---------|
| `spring.ai.openai.base-url` | `http://localhost:1234` | Ollama endpoint (no `/v1` suffix) |
| `spring.ai.openai.chat.options.model` | `google/gemma-4-26b-a4b-qat` | LLM model name |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/research-agent` | Database connection |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Schema validation only (no auto-modification) |
| `spring.ai.sse.timeout` | `600000` ms | SSE emitter timeout (10 min) |

### Frontend

```bash
cd research-agent-ui
npm install
npx ng serve --proxy-config proxy.conf.json    # starts on port 4200, proxies /api → localhost:8080
```

The frontend runs at `http://localhost:4200` and automatically forwards `/api/*` requests to the backend.

### Verify end-to-end

```bash
curl -X POST http://localhost:8080/api/research \
  -H "Content-Type: application/json" \
  -d '{"topic": "Test topic", "maxIterations": 2, "subTopicCount": 3}'
```

---

## 5. Where Everything Lives

### Backend (`research-agent-backend/src/main/java/com/researchagent/`)

| Package | What's in it |
|---------|--------------|
| `config/` | `ChatClientConfig` (OpenAI-compatible client), `AsyncConfig`, `WebConfig` (CORS) |
| `controller/` | `ResearchController` (REST lifecycle + history + bulk delete), `ResearchStreamController` (SSE endpoint) |
| `model/dto/` | Request/response contracts (`ResearchRequest`, `ResearchResponse`, `StepDTO`, `FollowUpRequest`, `ReportDTO`, `StreamUpdate`, `ResearchSessionDetailDTO`) |
| `model/entity/` | JPA entities (`ResearchSession`, `ResearchStep`) |
| `model/enums/` | `ResearchStatus` (PENDING/PROCESSING/COMPLETED/FAILED/CANCELLED), `StepType` |
| `repository/` | Spring Data JPA repositories |
| `service/` | `ResearchOrchestratorService` (core pipeline), `ResearchStreamingService` (SSE), `FollowUpService`, `LlmGateway` / `SpringAiLlmGateway` (LLM seam), `ResearchCancellationRegistry`, `AbandonedSessionCleanupService` |
| `tool/` | `WebSearchTool`, `UrlReaderTool`, `McpToolRouter` (search routing + fallbacks) |
| `src/main/resources/db/migration/` | `V1__init_research_tables.sql` — the only schema migration |

### Frontend (`research-agent-ui/src/app/`)

| Area | What's in it |
|------|--------------|
| `core/services/` | `research.service.ts` (REST + SSE with reconnection/polling fallback + signal state), `research-history.service.ts` (paginated history + deletion) |
| `core/models/` | `research.model.ts` — TypeScript interfaces mirroring the backend |
| `core/interceptors/` | `debug.interceptor.ts` |
| `features/research-input/` | New research form + quick-start chips |
| `features/active-research/` | Live streaming session view (step list + report viewer + follow-up) |
| `features/research-history/` | Paginated history with search + single/bulk delete |
| `features/history-detail/` | Historical session detail + follow-up |
| `shared/components/` | `step-list`, `report-viewer`, `followup-form` (all standalone) |
| `app.routes.ts` | Lazy-loaded route definitions |

---

## 6. Key Conventions (Read Before Coding)

### Backend
- **Constructor injection only** — no field-level `@Autowired`.
- **Lombok** (`@Data`, `@Slf4j`) reduces boilerplate — install the Lombok plugin in your IDE.
- **DTOs for all APIs** — never expose entities directly.
- **Enums for state** — use `ResearchStatus` / `StepType`, not string literals.
- Research tasks run on an async thread pool (core 5 / max 20). There is **no** OSIV/Hibernate session in the async flow — load via `findByIdWithSteps` (fetch-join) when mutating steps outside a transaction.

### Frontend
- **Standalone components only** — every `@Component` uses `standalone: true`. No NgModules.
- **Strict mode** — `strictTemplates` etc. are enforced by the TypeScript compiler.
- **Signal-based state** — prefer Angular Signals over `BehaviorSubject`.
- **DestroyRef cleanup** — use `inject(DestroyRef).onDestroy()` instead of `ngOnDestroy().unsubscribe()`.
- **M3 theming** — `mat.define-theme((color: ()))`; the `all-component-themes($theme)` mixin must be wrapped in a CSS selector (not at root).
- **Build gotcha** — only add `tsConfig` to the **build** builder in `angular.json`, NOT the serve builder.

### Database
- `ddl-auto: validate` — **no** automatic schema changes. Add a new migration under `db/migration/` for schema changes.
- `hibernate.jdbc.lob.non_contextual_creation=true` is required to avoid PostgreSQL LOB errors with TEXT columns.

---

## 7. Troubleshooting

| Symptom | Fix |
|---------|-----|
| Backend fails to start — schema mismatch | Fix entity annotations to match `db/migration/V1__init_research_tables.sql`, or temporarily set `ddl-auto: update` |
| SSE connection fails immediately | Confirm backend is on port 8080 and CORS allows `localhost:4200` |
| LLM returns empty responses | Verify the model is loaded (`ollama list`) and `OLLAMA_BASE_URL` matches the endpoint |
| Frontend proxy not working | Ensure the dev server uses `--proxy-config proxy.conf.json` |
| PostgreSQL LOB error in auto-commit mode | `hibernate.jdbc.lob.non_contextual_creation=true` is already set — confirm it is present |
| Abandoned sessions never cleaned up | Check `app.cleanup.stale-after` (default `PT15M`) and `app.cleanup.interval` (default `PT20M`) |

---

## 8. Full Documentation Index

| Document | Location | Purpose |
|----------|----------|---------|
| **Team & User Guide** (this file) | [TEAM.md](TEAM.md) | Human-friendly project overview — you are here |
| Project overview | [README.md](README.md) | Tech stack, quick start, architecture, docs index |
| AI agent guidance | [AGENTS.md](AGENTS.md) | Architecture + gotchas for AI coding agents |
| Claude Code guidance | [CLAUDE.md](CLAUDE.md) | Claude-specific repo guidance |
| Development guide | [DEVELOPMENT.md](DEVELOPMENT.md) | Setup, manual curl tests, debugging, conventions |
| Copilot instructions | [.github/copilot-instructions.md](.github/copilot-instructions.md) | Build/test/lint commands for GitHub Copilot |

### Backend detail docs (`research-agent-backend/docs/`)
| Document | Purpose |
|----------|---------|
| [ARCHITECTURE.md](research-agent-backend/docs/ARCHITECTURE.md) | Component layout, data flow, orchestration pipeline |
| [API.md](research-agent-backend/docs/API.md) | REST endpoints + SSE event types with examples |
| [MODELS.md](research-agent-backend/docs/MODELS.md) | Entities, DTOs, enums, internal models |
| [CONFIGURATION.md](research-agent-backend/docs/CONFIGURATION.md) | application.yml settings, dependencies, env vars |
| [DATABASE.md](research-agent-backend/docs/DATABASE.md) | Schema, migration, JPA/PostgreSQL notes |

### Frontend detail docs (`research-agent-ui/docs/`)
| Document | Purpose |
|----------|---------|
| [ARCHITECTURE.md](research-agent-ui/docs/ARCHITECTURE.md) | Component hierarchy, routing, SSE integration |
| [API.md](research-agent-ui/docs/API.md) | ResearchService methods + backend endpoint consumption |
| [STATE-MANAGEMENT.md](research-agent-ui/docs/STATE-MANAGEMENT.md) | Signal-based state, SSE→signal mapping, computed signals |
| [MODELS.md](research-agent-ui/docs/MODELS.md) | TypeScript interfaces mirroring the backend |
| [CONFIGURATION.md](research-agent-ui/docs/CONFIGURATION.md) | angular.json, tsconfig, proxy, build config |

---

## 9. Change Workflow

Changes follow the **OpenSpec** workflow, tracked under `openspec/changes/<name>/`. Each change includes a `proposal.md`, `design.md`, `specs/*.md`, and `tasks.md`. Use the spec-driven workflow (requirement analysis → clarification → spec generation → review → implementation → build-fix → verification) when making changes.
