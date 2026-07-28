# Research Agent Backend — Architecture

## Overview

The backend is a Spring Boot application that orchestrates AI-powered research tasks. It takes a user's research topic, breaks it down into sub-topics via an LLM with MCP tool-calling, searches the web for each sub-topic using configured search tools (MCP stdio servers + local Java fallback), reads content from multiple sources, synthesizes findings into a comprehensive report, and streams everything in real-time to the Angular frontend via SSE.

## System Architecture

```
┌─────────────┐          ┌──────────────────┐          ┌─────────────┐
│  Angular UI │─────────▶│   Spring Boot    │─────────▶│   Ollama    │
│  (port 4200)│ ◀────────│   Backend        │◀──────── │   LLM       │
│              │          │   (port 8080)    │          │  (port 1234)│
└─────────────┘          └──────────────────┘          └─────────────┘
                               │        │        │        │
                               ▼        ▼        ▼        ▼
                          ┌──────────────────────────────────┐
                          │      PostgreSQL                   │
                          │  (research-agent DB)              │
                          └──────────────────────────────────┘

          ┌─────────────────────────────────────────────┐
          │         MCP Tool Servers (stdio)             │
          │                                              │
          │  web_search → ollama_web_search.py           │
          │  searxng    → mcp-searxng                    │
          │  ddg_search → duckduckgo-mcp-server          │
          └─────────────────────────────────────────────┘
```

**Communication:**
- REST API: Angular → Backend (`/api/*` endpoints, proxied via `proxy.conf.json`)
- SSE streaming: Backend → Angular (real-time progress events)
- LLM calls: Backend → Ollama (OpenAI-compatible HTTP API)
- MCP tools: Backend → stdio servers for web search and content extraction

---

## Component Architecture

### 1. Controllers — REST + SSE Endpoints

#### `ResearchController` (`/api/research/*`)

Handles all REST endpoints for the research lifecycle:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/research` | POST | Start new session (returns immediately, async processing) |
| `/api/research/{sessionId}` | GET | Get current status with steps |
| `/api/research/{sessionId}` | DELETE | Cancel running session |
| `/api/research/history` | GET | Paginated history list |
| `/api/research/history/search` | GET | Search by topic name |
| `/api/research/history/{sessionId}` | GET | Get single historical session with steps (uses `ResearchSessionDetailDTO`) |
| `/api/research/history/bulk-delete` | POST | Bulk delete multiple sessions (all-or-nothing rollback) |
| `/api/research/history/{sessionId}` | DELETE | Delete single historical session (not PROCESSING only) |
| `/api/research/{sessionId}/report` | GET | Get final report text (falls back to generating from steps if missing) |
| `/api/research/{sessionId}/followup` | POST | Submit follow-up question about completed research |

#### `ResearchStreamController` (`/api/research/stream/{sessionId}`)

Placeholder SSE endpoint. Note: This controller creates a standalone `SseEmitter` that is **not wired** into the `ResearchStreamingService`. The actual SSE connections are managed by `ResearchStreamingService.registerStream()`, making this endpoint non-functional as written. The frontend uses `/api/research/stream/{sessionId}` but the backend's `ResearchOrchestratorService.processResearchAsync()` sends events directly through `ResearchStreamingService.sendReportChunk()` etc.

### 2. Services — Business Logic

#### `ResearchOrchestratorService` — Core Orchestration

The heart of the system. Implements a three-phase research pipeline:

```
Phase 1 — Topic Breakdown (non-streaming)
    ┌──────────┐      ┌─────┐
    │ LLM call │─────▶│ JSON│
    │ .call()  │      │ parse│
    └──────────┘      └─────┘

Phase 2 — Sub-topic Research (loop, non-streaming)
    FOR each sub-topic:
        ┌──────────┐     ┌──────────┐
        │ LLM call │────▶│ MCP tool│
        │ .call()  │     │ calling  │
        │          │     │(search+read)│
        └──────────┘     └──────────┘

Phase 3 — Final Report Synthesis (streaming)
    ┌──────────┐      ┌──────────┐
    │ LLM call │─────▶│ .stream()│
    │ .call()  │      │ .content()│──▶ Flux<String>
    └──────────┘      └──────────┘
```

**Key orchestration logic:**
- Parses JSON response from Phase 1 breakdown using Jackson `ObjectMapper`, with fallback creating a single generic SubTopic if parsing fails
- In Phase 2, limits iteration to `Math.min(subTopics.size(), maxIterations)` — whichever is smaller
- Uses MCP tool routing (`McpToolRouter`) for search tasks: routes "latest-information" → searxng→web_search chain; falls back to local Java tools when MCP unavailable
- Builds findings as markdown-formatted strings: `"## Sub-Topic: {title}\n\n{finding}"` for each sub-topic
- Phase 3 uses `.stream().content()` returning `Flux<String>` directly to stream chunks via SSE
- Accumulates report content in an `AtomicReference<StringBuilder>` during streaming, then saves the full text after completion

**Error handling:** Any exception mid-flow sets session status to FAILED, saves error as a BREAKDOWN step, and sends ERROR event via SSE.

#### `ResearchStreamingService` — SSE Connection Management

Thread-safe SSE emitter registry:
- `ConcurrentHashMap<UUID, SseEmitter>` maps sessions to their active connections
- Timeout configurable via `spring.ai.sse.timeout` (default 600000ms = 10 minutes)
- Completion/error/timeout callbacks automatically clean up the map entry
- Private `sendEvent(UUID, EventType, Object)` method serializes StreamUpdate DTOs and sends them as named SSE events

#### `FollowUpService` — Follow-up Question Handler

Retrieves a completed session's final report (truncated to 2000 chars for prompt context), constructs a system prompt with topic + truncated report + follow-up question, calls the LLM `.call()` method and returns the generated answer as plain text.

#### `AbandonedSessionCleanupService` — Stale Session Cleanup

Background scheduler that periodically marks PROCESSING sessions stuck longer than the configured threshold (default: 1 hour) as CANCELLED. Runs at a fixed interval (default: every 30 minutes). Configured via `application.yml`:
- `app.cleanup.stale-after: PT1H` — duration after which a session is considered stale
- `app.cleanup.interval: PT2M` — scheduler run interval

Uses `@Scheduled(fixedRateString = "${app.cleanup.interval}")` and queries `ResearchSessionRepository.findAllByStatusAndCreatedAtBefore()`.

### 3. Tool Definitions — LLM Function Calling + MCP Routing

#### `WebSearchTool` — Local Java Fallback
Defines tool methods annotated with Spring AI's `@Tool` annotation:
- **`search(String query)`** — Calls a web search API, formats results with titles, URLs, and snippets
- **`readUrl(String url)`** — Fetches content from a URL using HTTP + JSoup HTML parsing

#### `UrlReaderTool` — Local Java Fallback (URL Content Extraction)
Additional tool for extracting readable content from URLs. Works alongside `WebSearchTool`.

#### `McpToolRouter` — MCP Server Routing Logic
Routes LLM search tasks to appropriate MCP servers based on task type:
- `"latest-information"` → searxng → web_search chain
- `"general-search"` → searxng → web_search chain  
- `"search-fallback"` → searxng → web_search chain

The `McpToolRouter.getPreferredServer()` returns the first server in the chain (primary), while `getRoutingChain()` returns all fallback servers. The Spring AI MCP client handles the actual tool calling; this router is used to determine task type categorization.

### 4. Repositories — Data Access

#### `ResearchSessionRepository`
- Extends `JpaSpecificationExecutor<ResearchSession>` for dynamic query building
- Custom method: `findByIdWithSteps(UUID)` — fetches session with eager-load of the steps collection (avoids N+1 lazy-loading)
- Custom method: `findAllByStatusAndCreatedAtBefore(ResearchStatus, LocalDateTime)` — used by cleanup scheduler to find abandoned sessions

#### `ResearchStepRepository`
- Extends `JpaRepository<ResearchStep, UUID>`
- Custom method: `findBySessionOrderByOrderIndex(UUID)` — fetches all steps for a session ordered by index

### 5. Configuration Classes

#### `AsyncConfig`
Creates a named ThreadPoolTaskExecutor bean (`researchTaskExecutor`) with corePoolSize=5, maxPoolSize=20, queueCapacity=100, thread name prefix "research-". Used to execute research tasks asynchronously outside the request thread. Note: `@EnableAsync` is present but no `@Async` methods are implemented — execution is manual via injected executor.

#### `ChatClientConfig`
Creates a basic ChatClient bean via Spring AI's auto-configured beans. No manual configuration needed for OpenAI-compatible endpoints — Spring AI detects properties automatically. Minimal setup; MCP tools are configured separately via `application.yml`.

#### `WebConfig`
CORS configuration allowing origins `http://localhost:4200`, `http://127.0.0.1:4200` (Angular dev server). Allows GET, POST, PUT, DELETE, OPTIONS methods with all headers.

### 6. Domain Models — JPA Entities

#### `ResearchSession`
Top-level entity representing a research task:
- Bidirectional OneToMany with ResearchStep (CascadeType.ALL, orphanRemoval=true)
- Lifecycle callbacks: `@PrePersist` sets createdAt, `@PreUpdate` updates updatedAt
- Convenience methods: `addStep()`, `complete()`, `fail(errorMessage)` — encapsulates state transitions

#### `ResearchStep`
Represents an individual step in the research pipeline:
- ManyToOne to ResearchSession (LAZY fetch)
- Unique constraint on `(session_id, order_index)` pair ensures ordering within a session
- Status stored as raw String ("PENDING"/"RUNNING"/"COMPLETED"/"FAILED") — inconsistent with how `ResearchStatus` enum is used elsewhere

---

## Data Flow: Starting a Research Session

```
1. POST /api/research (Angular) → Spring Boot
2. ResearchController.startResearch()
    ├── createAndStart(request) — persist session with status PROCESSING
    └── processResearchAsync(sessionId, request)  // async via executor bean

3. Controller returns HTTP 201 CREATED immediately

4. Async processing (separate thread):
   Phase 1:
     ChatClient.call(breakdownPrompt) → LLM parses topic into SubTopics[]
     Save BREAKDOWN step with JSON content
   
   Phase 2 (loop, up to Math.min(subTopics.size(), maxIterations)):
     ResearchStreamingService.sendProgress(sessionId, "Researching: {title}")
     Save SUBTOPIC step as RUNNING
     ChatClient.call(researchPrompt) → MCP tool-calling for search+read
       McpToolRouter routes by task type; local tools used as fallback
     Collect finding into allFindings list
     Update step to COMPLETED
     sendProgress("Completed research on: {title}")
   
   Phase 3:
     ResearchStreamingService.sendReportStart(sessionId)
     ChatClient.stream().content(synthesisPrompt) → Flux<String> chunks

5. For each chunk from Flux:
      AtomicReference<StringBuilder>.get().append(chunk)
      sendReportChunk(sessionId, chunk) via SSE

6. On completion:
      session.complete() — set status COMPLETED, completedAt timestamp
      Save FINAL_REPORT step with full content
      ResearchStreamingService.sendReportDone(sessionId, fullReport)

7. If any exception occurs mid-flow:
      session.fail(errorMsg) — set status FAILED, error in finalReport
      Send ERROR event via SSE
```

---

## Configuration Reference

See [CONFIGURATION.md](./CONFIGURATION.md) for complete settings reference including environment variables, dependencies, MCP server configurations, and async execution details.
