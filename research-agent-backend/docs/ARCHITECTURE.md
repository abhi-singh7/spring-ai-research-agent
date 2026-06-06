# Research Agent Backend — Architecture

## Overview

The backend is a Spring Boot application that orchestrates AI-powered research tasks. It takes a user's research topic, breaks it down into sub-topics via an LLM, searches the web for each sub-topic using tool-calling, reads content from multiple sources, synthesizes findings into a comprehensive report, and streams everything in real-time to the Angular frontend via SSE.

## System Architecture

```
┌─────────────┐          ┌──────────────────┐          ┌─────────────┐
│  Angular UI │─────────▶│   Spring Boot    │─────────▶│   Ollama    │
│  (port 4200)│ ◀────────│   Backend        │◀──────── │   LLM       │
│              │          │   (port 8080)    │          │  (port 1234)│
└─────────────┘          └──────────────────┘          └─────────────┘
                              │        │        │
                              ▼        ▼        ▼
                         ┌──────────────────────────┐
                         │     PostgreSQL           │
                         │  (research-agent DB)     │
                         └──────────────────────────┘
```

**Communication:**
- REST API: Angular → Backend (`/api/*` endpoints, proxied via `proxy.conf.json`)
- SSE streaming: Backend → Angular (real-time progress events)
- LLM calls: Backend → Ollama (OpenAI-compatible HTTP API at `/v1`)

---

## Component Architecture

### 1. Controllers — REST + SSE Endpoints

#### `ResearchController` (`/api/research/*`)
Handles all REST endpoints for research lifecycle management:
- **POST** `/api/research` — Start new session (returns immediately, async processing)
- **GET** `/api/research/{sessionId}` — Get current status with steps
- **DELETE** `/api/research/{sessionId}` — Cancel running session
- **GET** `/api/research/history` — Paginated history list
- **GET** `/api/research/history/search` — Search by topic name
- **GET** `/api/research/{sessionId}/report` — Get final report text
- **POST** `/api/research/{sessionId}/followup` — Submit follow-up question

#### `ResearchStreamController` (`/api/research/stream/{sessionId}`)
Placeholder SSE endpoint. Note: This controller creates a standalone `SseEmitter` that is **not wired** into the `ResearchStreamingService`. The actual SSE connections are managed by `ResearchStreamingService.registerStream()`, making this endpoint non-functional as written.

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
        │ LLM call │────▶│ tool-calling│
        │ .call()  │     │ (search+read)│
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
- Builds findings as markdown-formatted strings: `"## Sub-Topic: {title}\n\n{finding}"` for each sub-topic
- Phase 3 uses `.stream().content()` returning `Flux<String>` directly (not `.stream().map()`) to stream chunks via SSE
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

### 3. Tool Definitions — LLM Function Calling

#### `WebSearchTool`
Defines two tool methods annotated with Spring AI's `@Tool` annotation:
- **`search(String query)`** — Calls a web search API, formats results with titles, URLs, and snippets
- **`readUrl(String url)`** — Fetches content from a URL using HTTP + JSoup HTML parsing

**Critical issue:** The `@Tool` annotations are defined but the tools are not wired into the ChatClient. There is no configuration (no `ChatClient.builder().defaultTools()`) that registers these tool callbacks. Research phases 2+ will not actually invoke web search/URL reading — this feature appears incomplete.

### 4. Repositories — Data Access

#### `ResearchSessionRepository`
- Extends `JpaSpecificationExecutor<ResearchSession>` for dynamic query building
- Custom method: `findByIdWithSteps(UUID)` — fetches session with eager-load of the steps collection (avoids N+1 lazy-loading)
- Standard Spring Data JPA methods inherited (save, findById, deleteById, etc.)

#### `ResearchStepRepository`
- Extends `JpaRepository<ResearchStep, UUID>`
- Custom method: `findBySessionOrderByOrderIndex(UUID)` — fetches all steps for a session ordered by index

### 5. Configuration Classes

#### `AsyncConfig`
Creates a named ThreadPoolTaskExecutor bean (`researchTaskExecutor`) with:
- corePoolSize=5, maxPoolSize=20, queueCapacity=100
- Thread name prefix: "research-"
- Used to execute research tasks asynchronously outside the request thread

**Note:** The `@EnableAsync` annotation is present but no `@Async` methods are actually implemented — the async execution in `ResearchOrchestratorService.processResearchAsync()` is manual (calling a method on the injected executor), not declarative.

#### `ChatClientConfig`
Creates a basic ChatClient bean via Spring AI's auto-configured beans:
- No manual configuration needed for OpenAI-compatible endpoints — Spring AI detects the properties automatically
- No tool registration or default tools configured here

**Note:** This is a minimal setup. The full production implementation would need to wire up tool callbacks and configure chat options (model, temperature) explicitly.

#### `WebConfig`
CORS configuration:
- Allows origins: `http://localhost:4200`, `http://127.0.0.1:4200` (Angular dev server)
- Allowed methods: GET, POST, PUT, DELETE, OPTIONS
- All headers allowed

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
- Status is stored as raw String ("PENDING"/"RUNNING"/"COMPLETED"/"FAILED") — inconsistent with how ResearchStatus enum is used elsewhere

**Known issue:** The `status` field default value `"PENDING"` uses a hardcoded string instead of the enum constant `ResearchStatus.PENDING`. This inconsistency could cause issues if the enum values are ever changed.

---

## Data Flow: Starting a Research Session

```
1. POST /api/research (Angular) → Spring Boot
2. ResearchController.createAndStart()
   ├── createSession() — persist session with status PROCESSING
   └── processResearchAsync(sessionId, request)  // async execution
3. Controller returns HTTP 201 CREATED immediately

4. Async processing (separate thread):
   Phase 1:
     ChatClient.call(breakdownPrompt) → LLM parses topic into SubTopics[]
     Save BREAKDOWN step with JSON content
   
   Phase 2 (loop, up to Math.min(subTopics.size(), maxIterations)):
     ResearchStreamingService.sendProgress(sessionId, "Researching: {title}")
     Save SUBTOPIC step as RUNNING
     ChatClient.call(researchPrompt) → LLM uses tool-calling for search+read
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

### `application.yml` Key Settings

| Setting | Value | Description |
|---------|-------|-------------|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/research-agent` | PostgreSQL database connection |
| `spring.datasource.username` | `${DB_USERNAME}` (env) | Database username (default: postgres) |
| `spring.datasource.password` | `${DB_PASSWORD}` (env) | Database password (default: postgres) |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Schema validation only — no auto-creation/modification |
| `spring.jpa.properties.hibernate.dialect` | `org.hibernate.dialect.PostgreSQLDialect` | Hibernate dialect for PostgreSQL |
| `openai.api.key` | `${OPENAI_API_KEY}` (env) | API key for OpenAI-compatible LLM |
| `openai.base-url` | `${OLLAMA_BASE_URL}` (env, default: http://localhost:1234/v1) | Base URL for Ollama REST API |
| `openai.chat.options.model` | `${LLM_MODEL}` (env, default: llama3.1) | LLM model name |
| `openai.chat.options.temperature` | `0.7` | Temperature for text generation |
| `spring.ai.task-executor.core-pool-size` | `5` | Async task executor core thread pool size |
| `spring.ai.task-executor.max-pool-size` | `20` | Max thread pool size |
| `spring.ai.sse.timeout` | `600000` (ms) | SSE emitter timeout — 10 minutes of inactivity |

### Environment Variables Required

| Variable | Description | Default |
|----------|-------------|---------|
| `DB_USERNAME` | PostgreSQL username | postgres |
| `DB_PASSWORD` | PostgreSQL password | postgres |
| `OPENAI_API_KEY` | API key for LLM endpoint | (none — required) |
| `OLLAMA_BASE_URL` | Base URL for Ollama REST API | http://localhost:1234/v1 |
| `LLM_MODEL` | Model name to use | llama3.1 |

### Dependencies Summary

| Dependency | Purpose |
|-----------|---------|
| spring-boot-starter-parent 3.2.5 | Spring Boot project parent |
| spring-ai-bom 1.0.0 (via property) | Spring AI dependency management BOM — **GA release** |
| spring-ai-starter-model-openai | Auto-configures OpenAI-compatible chat model client from properties |
| spring-boot-starter-web | REST web server support |
| spring-boot-starter-webflux | Reactive/WebFlux for streaming SSE endpoints |
| spring-boot-starter-data-jpa | JPA data access via Spring Data JPA |
| postgresql | PostgreSQL JDBC driver |
| flyway-core | Database migration management |
| spring-boot-starter-validation | Bean validation (@NotBlank, @Min) |
| jsoup 1.17.2 | HTML content extraction for web scraping (declared but not directly used in any scanned file) |
| lombok | Reduces boilerplate (@Data, @Slf4j) |
