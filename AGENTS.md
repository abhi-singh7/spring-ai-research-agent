# AGENTS.md — Research Agent

## Architecture

Full-stack research tool: user submits a topic → LLM breaks it into sub-topics → searches the web with tool calling → reads content from multiple sources → synthesizes findings → displays via SSE streaming.

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

Requires PostgreSQL and a local LLM endpoint — configured in `src/main/resources/application.yml`. Default model: `google/gemma-4-e4b` (commented alternative: `qwopus3.6-35b-a3b-v1`).

### Frontend (`research-agent-ui/`)
```bash
npm start                     # proxy.conf.json forwards /api → localhost:8080
# or: npx ng serve --proxy-config proxy.conf.json
```

## Key Implementation Gotchas

- **Spring AI 1.1.7** uses `spring-ai-starter-model-openai` artifact (NOT `spring-ai-openai-spring-boot-starter`). Config classes are auto-configured — no bean definitions needed. See ChatClientConfig.java.
- **Angular standalone components**: All `@Component` decorators must include `standalone: true` when using the `imports` property.
- **Angular Material M3 theming**: Use `mat.define-theme((color: ()))`. The `all-component-themes($theme)` mixin must be wrapped in a CSS selector — cannot be called at root level. See styles.scss.
- **SSE streaming**: Backend uses `Flux<String>` via `.stream().content()`; frontend detects `EventSource.readyState === CLOSED` and switches to polling on reconnect.
- **PostgreSQL DDL-auto: `validate`** — no automatic schema generation. Changes require manual migration or disabling validate mode in dev.
- **No lint/test/typecheck scripts defined** in package.json or pom.xml. Angular karma tests configured but no npm script for them either.

## MCP Tool Server Connections

Four stdio-based MCP servers auto-configured via `application.yml`:

| Server | Command | Purpose |
|--------|---------|---------|
| web_search | `uv run /home/abhi/ollama_web_search.py` | Web search (requires OLLAMA_API_KEY) |
| searxng | `npx -y mcp-searxng` | SearXNG search (requires SEARXNG_URL) |
| excalidraw | `node /home/abhi/excalidraw-mcp/dist/index.js --stdio` | Diagram generation |
| ddg_search | `uvx duckduckgo-mcp-server` | DuckDuckGo fallback search |

Local Java tools (WebSearchTool, UrlReaderTool) serve as fallback when MCP servers are unavailable. See ChatClientConfig.java for tool routing logic.

## Key Files

### Backend
| File | Purpose |
|------|---------|
| `src/main/java/com/researchagent/config/ChatClientConfig.java` | ChatClient + MCP validation + tool router beans |
| `src/main/resources/application.yml` | MCP connections, LLM model, datasource, SSE timeout (600s) |
| `src/main/java/com/researchagent/service/ResearchOrchestratorService.java` | Core: topic → sub-topics → tool calling → final report |
| `src/main/java/com/researchagent/service/ResearchStreamingService.java` | SSE via `ConcurrentHashMap<UUID, SseEmitter>`, events: PROGRESS, CONTENT, REPORT_CHUNK, REPORT_DONE, STEP_COMPLETE, ERROR |
| `src/main/java/com/researchagent/controller/ResearchController.java` | REST endpoints for research lifecycle + history |
| `src/main/java/com/researchagent/controller/ResearchStreamController.java` | SSE endpoint at `/api/research/stream/{sessionId}` |

### Frontend
| File | Purpose |
|------|---------|
| `src/app/app.routes.ts` | Routing (see CLAUDE.md) |
| `src/app/features/active-research/` | Live streaming session view |
| `src/app/features/research-input/` | New research form with quick-start chips |
| `src/app/features/research-history/` | Paginated history with search/filter |
| `src/app/shared/components/step-list/` | Step progress display |
| `src/app/shared/components/report-viewer/` | Report content renderer |
| `src/app/shared/components/followup-form/` | Inline follow-up question form |
| `src/app/core/services/research.service.ts` | Primary service: REST + SSE via EventSource, signal state management (researchSession, researchSteps, reportContent, isStreaming), polling fallback on disconnect |

## Conventions

- All frontend components are standalone — no NgModules.
- Angular strict mode enabled (`strictInjectionParameters`, `strictInputAccessModifiers`, `strictTemplates`).
- No CI workflows exist in this repo.
