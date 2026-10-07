# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> New to the project or onboarding? Start with [TEAM.md](../TEAM.md) — a human-friendly overview of what the app does, how to run it, and where everything lives.

## Architecture Overview

The Research Agent application is a full-stack system that takes a user's research topic, autonomously breaks it down into sub-topics, searches the web for each using an LLM with MCP tool calling, reads content from multiple sources, synthesizes findings into a comprehensive report, and displays everything in real-time via SSE streaming.

**Tech Stack:**
- Backend: Java 21 + Spring Boot 4.1.1 + Spring AI 2.0.1 (OpenAI-compatible local LLM) — `research-agent-backend/`
- Frontend: Angular 18+ with Signals/RxJS + Angular Material M3 — `research-agent-ui/`
- Database: MongoDB (single `research_session` collection, steps embedded in the session document)

## Running the Application

### Backend (`research-agent-backend/`)
```bash
# Set Java home to JDK 21
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# Start backend on port 8080 (default)
mvn spring-boot:run

# Verify running
curl http://localhost:8080/api/research/history
```

Requires MongoDB (`mongodb://localhost:27017/research-agent` — the database is created automatically) and a local LLM endpoint, configured in `application.yml`.

### Frontend (`research-agent-ui/`)
```bash
# Start dev server with proxy to backend on port 8080
npx ng serve --proxy-config proxy.conf.json

# Runs on http://localhost:4200
```

The proxy config forwards `/api` requests to the Spring Boot backend.

## Key Backend Files

- `src/main/java/com/researchagent/config/ChatClientConfig.java` — Configures OpenAI-compatible chat client via auto-configured beans (no manual bean definition needed)
- `src/main/resources/application.yml` — MCP connections, LLM model, MongoDB URI, SSE timeout (600s), cleanup scheduler config
- `src/main/java/com/researchagent/service/ResearchOrchestratorService.java` — Core orchestrator: breaks down topic into sub-topics, processes each with tool calling, generates final report. Uses `.stream().content()` returning `Flux<String>` for LLM streaming responses
- `src/main/java/com/researchagent/service/ResearchStreamingService.java` — SSE connection management via `ConcurrentHashMap<UUID, SseEmitter>`, sends typed events: PROGRESS, CONTENT, REPORT_CHUNK, REPORT_DONE, STEP_COMPLETE, ERROR
- `src/main/java/com/researchagent/controller/ResearchController.java` — REST endpoints for research lifecycle + history + bulk delete (single and multi-select)
- `src/main/java/com/researchagent/tool/McpToolRouter.java` — Routes search tasks to MCP server chains with fallbacks
- `src/main/java/com/researchagent/service/AbandonedSessionCleanupService.java` — Background scheduler marks stuck PROCESSING sessions as CANCELLED
- `src/main/java/com/researchagent/advisor/LoggingAdvisor.java` — Logs every LLM request/response pair to the `llm_logs` Mongo collection (both `.call()` and `.stream()` paths) with per-research-session attribution; renders tool calls/tool results explicitly so intermediate tool rounds aren't blank
- `src/main/java/com/researchagent/repository/LlmLogRepository.java` — Per-session LLM history queries (`findBySessionIdOrderByCreatedAtAsc`, `countBySessionId`, `deleteBySessionId`)

## Key Frontend Files

### Routing (`research-agent-ui/src/app/app.routes.ts`)
```typescript
{ path: '', redirectTo: 'research/new', pathMatch: 'full' }
{ path: 'research/new', loadComponent: () => import('./features/research-input/research-input.component') }
{ path: 'research/:sessionId', loadComponent: () => import('./features/active-research/active-research.component') }
{ path: 'research/history', loadComponent: () => import('./features/research-history/research-history.component') }
{ path: 'research/history/:sessionId', loadComponent: () => import('./features/history-detail/history-detail.component') }
```

### Components (all standalone)
- `src/app/features/active-research/` — Live streaming session view with step list, report viewer, cancel/follow-up forms
- `src/app/features/research-input/` — New research form with topic input and quick-start chips
- `src/app/features/research-history/` — Paginated history with search/filter
- `src/app/features/history-detail/` — Historical session detail + follow-up

### Shared Components (all standalone)
- `src/app/shared/components/step-list/` — Step progress display with status icons and animations
- `src/app/shared/components/report-viewer/` — Report content renderer (raw text fallback)
- `src/app/shared/components/followup-form/` — Inline follow-up question form

### Services
- `src/app/core/services/research.service.ts` — Primary service: REST calls + SSE via EventSource API. Manages signal state (researchSession, researchSteps, reportContent, isStreaming). Includes polling fallback when SSE disconnects.
- `src/app/core/services/research-history.service.ts` — Paginated history queries

### Configuration
- `proxy.conf.json` — Dev server proxy: `/api` → `http://localhost:8080`
- `tsconfig.app.json` — Required for Angular build (NOT the serve builder)
- `src/styles.scss` — M3 theming via `mat.define-theme()` wrapped in a CSS selector

## Important Implementation Details

1. **Spring AI 2.0** uses `spring-ai-starter-model-openai` artifact, not the old milestone name. Config classes are auto-configured — no bean definitions needed. See ChatClientConfig.java.
2. **Search backend routing**: Search backends are executed by WebSearchTool over HTTP in ONE tool call: `firecrawl → ddg → ollama_web_search → tavily` (see McpToolRouter). Firecrawl is a self-hosted API (`app.search.firecrawl-base-url`, default `http://localhost:3002`) — NOT an MCP server. Two stdio MCP servers (`ollama_web_search`, `ddg_search`) are additionally configured in application.yml; UrlReaderTool escalates to Firecrawl's `/v2/scrape` when Jsoup can't read a page.
3. **Angular standalone components**: All `@Component` decorators must include `standalone: true` when using the `imports` property.
4. **tsConfig in angular.json**: Only add `tsConfig` to the `build` builder options, NOT the `serve` builder (schema validation error).
5. **Angular Material M3 theming**: Use `mat.define-theme((color: ()))` for default theme. The `all-component-themes($theme)` mixin must be wrapped in a CSS selector — it cannot be called at root level.
6. **SSE resilience**: Frontend uses exponential backoff reconnection (max 3 attempts) before falling back to polling. A 90-second stall timer forces completion detection if no chunks arrive during report generation.
7. **Abandoned session cleanup**: `AbandonedSessionCleanupService` marks PROCESSING sessions stuck >`app.cleanup.stale-after` (default `PT15M`) as CANCELLED every `app.cleanup.interval` (default `PT20M`). Configurable via `app.cleanup.stale-after` and `app.cleanup.interval`.
8. **MongoDB storage (migrated from PostgreSQL)**: sessions are stored in a single `research_session` collection with steps embedded in the document — no schema migrations; indexes are auto-created from entity annotations. UUIDs use the STANDARD BSON representation (`spring.mongodb.representation.uuid: STANDARD`), kept in sync with the one-shot migration script `scripts/migrate_pg_to_mongo.py`. The legacy PG schema is retained untouched as a rollback path.
9. **LLM call logging**: `LoggingAdvisor` persists every LLM request/response pair to the `llm_logs` collection. It implements both `CallAdvisor` and `StreamAdvisor` (Spring AI 2.0 routes `.call()`/`.stream()` through separate advisor chains) and is registered explicitly via `.defaultAdvisors(...)` on the ChatClient — Spring AI 2.0 does not auto-pick-up Advisor beans. With order 0 it sits inside ToolCallingAdvisor, so each model round of a tool-calling exchange gets its own document; tool calls and results are rendered with explicit markers because Spring AI keeps them outside `Message.getText()`. Session attribution is threaded through the advisor context (`LlmGateway` methods take the session id first — not ThreadLocal, since stream callbacks run on Reactor threads). Persist failures are swallowed so logging never breaks the pipeline. Per-session history: `LlmLogRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)`.
