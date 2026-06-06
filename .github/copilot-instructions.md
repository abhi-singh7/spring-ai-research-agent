# Copilot Instructions — Research Agent

## Build, Test, and Lint Commands

### Backend (`research-agent-backend/`)
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=ResearchOrchestratorServiceTest

# Run a specific test method
mvn test -Dtest=ResearchOrchestratorServiceTest#testMethodName

# Build without running tests
mvn package -DskipTests

# Start the backend on port 8080 (default)
mvn spring-boot:run

# Verify it's running
curl http://localhost:8080/api/research/history
```

### Frontend (`research-agent-ui/`)
```bash
# Start dev server with proxy to backend
npx ng serve --proxy-config proxy.conf.json

# Run on http://localhost:4200 — requests to /api are proxied to http://localhost:8080

# Build the app (production)
npx ng build

# Lint
npx ng lint

# Run unit tests
npx ng test

# Run a single test file
npx ng test --include src/app/core/services/research.service.spec.ts
```

## High-Level Architecture

The Research Agent is a full-stack system that takes a user's research topic, autonomously breaks it down into sub-topics, searches the web for each using an LLM with tool calling, reads content from multiple sources, synthesizes findings into a comprehensive report, and displays everything in real-time via SSE streaming.

**Tech Stack:**
- **Backend:** Java 21 + Spring Boot 3.2.5 + Spring AI 1.0 GA (OpenAI-compatible local LLM) — `research-agent-backend/`
- **Frontend:** Angular 18+ with Signals/RxJS + Angular Material M3 — `research-agent-ui/`
- **Database:** PostgreSQL

### Backend Flow

```
ResearchController → ResearchOrchestratorService → ResearchStreamingService (SSE)
                      ↓                              ↓
               ChatClientConfig                  SSE events
                      ↓                              PROGRESS, CONTENT,
              WebSearchTool                        REPORT_CHUNK, REPORT_DONE
                      ↓                              STEP_COMPLETE, ERROR
              LLM (Ollama/compatible)
```

**Key Backend Files:**
- `src/main/java/com/researchagent/config/ChatClientConfig.java` — Auto-configures OpenAI-compatible chat client via Spring AI beans (no manual bean definition needed)
- `src/main/java/com/researchagent/service/ResearchOrchestratorService.java` — Core orchestrator: breaks down topic into sub-topics, processes each with tool calling, generates final report. Uses `.stream().content()` returning `Flux<String>` for LLM streaming responses
- `src/main/java/com/researchagent/service/ResearchStreamingService.java` — SSE connection management via `ConcurrentHashMap<UUID, SseEmitter>`, sends typed events: PROGRESS, CONTENT, REPORT_CHUNK, REPORT_DONE, STEP_COMPLETE, ERROR
- `src/main/java/com/researchagent/controller/ResearchController.java` — REST endpoints for research lifecycle and history
- `src/main/java/com/researchagent/controller/ResearchStreamController.java` — SSE endpoint at `/api/research/stream/{sessionId}`
- `src/main/java/com/researchagent/tool/WebSearchTool.java` — LLM tool definition for web search capability

### Frontend Flow

```
ActiveResearchComponent ← ResearchService (SSE via EventSource + polling fallback)
         ↓                    (signal state: researchSession, researchSteps, reportContent, isStreaming)
StepListComponent       ReportViewerComponent  FollowupFormComponent
```

**Key Frontend Files:**
- `src/app/core/services/research.service.ts` — Primary service: REST calls + SSE via EventSource API. Manages signal state (researchSession, researchSteps, reportContent, isStreaming). Includes polling fallback when SSE disconnects
- `src/app/features/active-research/` — Live streaming session view with step list, report viewer, cancel/follow-up forms
- `src/app/shared/components/step-list/` — Step progress display with status icons and animations
- `src/app/shared/components/report-viewer/` — Report content renderer (raw text fallback)

### Routing (`research-agent-ui/src/app/app.routes.ts`)
```typescript
{ path: '', redirectTo: 'research/new', pathMatch: 'full' }
{ path: 'research/new', loadComponent: () => import('./features/research-input/research-input.component') }
{ path: 'research/:sessionId', loadComponent: () => import('./features/active-research/active-research.component') }
{ path: 'research/history', loadComponent: () => import('./features/research-history/research-history.component') }
{ path: 'research/history/:sessionId', loadComponent: () => import('./features/history-detail/history-detail.component') }
```

## Key Conventions

### Backend Conventions

1. **Spring AI 1.0 GA** — Uses `spring-ai-starter-model-openai` artifact, not `spring-ai-openai-spring-boot-starter`. Manual config classes (OpenAiApiProperties, OpenAiChatModel) are auto-configured — no bean definitions needed.
2. **Reactive streaming** — LLM responses use `.stream().content()` returning `Flux<String>`, never blocking calls. SSE endpoints return `SseEmitter` from `ResearchStreamingService`.
3. **DTO pattern** — All API inputs/outputs use DTOs (e.g., `ResearchRequest`, `ResearchResponse`, `StepDTO`). Never expose entity classes directly in APIs.
4. **Enum types for state** — Use `ResearchStatus` and `StepType` enums consistently, not string literals.
5. **Async task execution** — Research tasks run asynchronously via the configured thread pool (core-pool-size: 5, max-pool-size: 20). See `AsyncConfig.java`.

### Frontend Conventions

1. **Standalone components only** — All `@Component` decorators must include `standalone: true` when using the `imports` property. No NgModules.
2. **Signal-based state management** — Use Angular Signals for reactive UI state, not RxJS BehaviorSubjects (except in SSE service where EventSource is used).
3. **SSE resilience pattern** — Frontend detects `EventSource.readyState === CLOSED` and switches to polling mode on reconnect. Always implement this fallback.
4. **M3 Material theming** — Use `mat.define-theme((color: ()))` for default theme. The `all-component-themes($theme)` mixin must be wrapped in a CSS selector — it cannot be called at root level.
5. **Proxy configuration** — Dev server proxy config (`proxy.conf.json`) forwards `/api` requests to the Spring Boot backend on port 8080.

### Configuration Conventions

- All environment variables use `${VAR_NAME:default_value}` syntax in `application.yml` (e.g., `jdbc:postgresql://localhost:5432/research-agent`, username defaults to `postgres`, password defaults to `postgres`).
- LLM endpoint defaults to Ollama at `http://localhost:1234/v1`. Override with `OLLAMA_BASE_URL` env var.

### Important Angular Build Gotchas

- Only add `tsConfig` to the **build** builder options in `angular.json`, NOT the serve builder (schema validation error).
- The serve builder should use `outputPath: "dist"` and not reference tsconfig directly — it will fail validation.
