# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Architecture Overview

The Research Agent application is a full-stack system that takes a user's research topic, autonomously breaks it down into sub-topics, searches the web for each using an LLM with MCP tool calling, reads content from multiple sources, synthesizes findings into a comprehensive report, and displays everything in real-time via SSE streaming.

**Tech Stack:**
- Backend: Java 21 + Spring Boot 3.5.4 + Spring AI 1.1.7 (OpenAI-compatible local LLM) — `research-agent-backend/`
- Frontend: Angular 18+ with Signals/RxJS + Angular Material M3 — `research-agent-ui/`
- Database: PostgreSQL

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

Requires PostgreSQL and a local LLM endpoint (e.g., Ollama at `http://localhost:1234/v1`) configured in `application.yml`.

### Frontend (`research-agent-ui/`)
```bash
# Start dev server with proxy to backend on port 8080
npx ng serve --proxy-config proxy.conf.json

# Runs on http://localhost:4200
```

The proxy config forwards `/api` requests to the Spring Boot backend.

## Key Backend Files

- `src/main/java/com/researchagent/config/ChatClientConfig.java` — Configures OpenAI-compatible chat client via auto-configured beans (no manual bean definition needed)
- `src/main/resources/application.yml` — MCP connections, LLM model, datasource, SSE timeout (600s), cleanup scheduler config
- `src/main/java/com/researchagent/service/ResearchOrchestratorService.java` — Core orchestrator: breaks down topic into sub-topics, processes each with tool calling, generates final report. Uses `.stream().content()` returning `Flux<String>` for LLM streaming responses
- `src/main/java/com/researchagent/service/ResearchStreamingService.java` — SSE connection management via `ConcurrentHashMap<UUID, SseEmitter>`, sends typed events: PROGRESS, CONTENT, REPORT_CHUNK, REPORT_DONE, STEP_COMPLETE, ERROR
- `src/main/java/com/researchagent/controller/ResearchController.java` — REST endpoints for research lifecycle + history + bulk delete (single and multi-select)
- `src/main/java/com/researchagent/tool/McpToolRouter.java` — Routes search tasks to MCP server chains with fallbacks
- `src/main/java/com/researchagent/service/AbandonedSessionCleanupService.java` — Background scheduler marks stuck PROCESSING sessions as CANCELLED

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

1. **Spring AI 1.1.7** uses `spring-ai-starter-model-openai` artifact, not the old milestone name. Config classes are auto-configured — no bean definitions needed. See ChatClientConfig.java.
2. **MCP tool routing**: Four stdio-based MCP servers (`web_search`, `searxng`, `excalidraw`, `ddg_search`) configured in application.yml. The `McpToolRouter` component routes search tasks to preferred server chains with fallbacks. Local Java tools (WebSearchTool, UrlReaderTool) serve as fallback when MCP servers are unavailable.
3. **Angular standalone components**: All `@Component` decorators must include `standalone: true` when using the `imports` property.
4. **tsConfig in angular.json**: Only add `tsConfig` to the `build` builder options, NOT the `serve` builder (schema validation error).
5. **Angular Material M3 theming**: Use `mat.define-theme((color: ()))` for default theme. The `all-component-themes($theme)` mixin must be wrapped in a CSS selector — it cannot be called at root level.
6. **SSE resilience**: Frontend uses exponential backoff reconnection (max 3 attempts) before falling back to polling. A 90-second stall timer forces completion detection if no chunks arrive during report generation.
7. **Abandoned session cleanup**: `AbandonedSessionCleanupService` marks PROCESSING sessions stuck >1 hour as CANCELLED every 30 minutes. Configurable via `app.cleanup.stale-after` and `app.cleanup.interval`.
8. **PostgreSQL DDL-auto: `validate`** — no automatic schema generation. Changes require manual migration or disabling validate mode in dev. `hibernate.jdbc.lob.non_contextual_creation=true` is required to avoid PostgreSQL LOB API errors.
