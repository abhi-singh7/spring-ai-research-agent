# Production Readiness Specification — Research Agent Backend

## 1. API Validation Requirements

### 1.1 POST /api/research (Start Research)
- [ ] Happy path: valid request returns 201 with session ID, topic, status
- [ ] Validation: blank/missing `topic` → 400
- [ ] Validation: `maxIterations < 1` → 400
- [ ] Validation: `subTopicCount < 1` → 400
- [ ] Error path: null request body → 400

### 1.2 GET /api/research/{sessionId} (Get Status)
- [ ] Happy path: existing session returns 200 with status, topic, report if available
- [ ] Not found: non-existent ID → 404
- [ ] Steps included for COMPLETED sessions

### 1.3 DELETE /api/research/{sessionId} (Cancel Research)
- [ ] Happy path: PROCESSING session returns 204
- [ ] Error: non-PROCESSING session → 400
- [ ] Error: non-existent session → 400

### 1.4 GET /api/research/history (Get History)
- [ ] Happy path: paginated results, sorted by createdAt DESC
- [ ] Empty page when no sessions exist

### 1.5 GET /api/research/history/search?query=... (Search History)
- [ ] Happy path: returns matching sessions
- [ ] No match: empty content array, still 200

### 1.6 GET /api/research/history/{sessionId} (Get Detail)
- [ ] Happy path: includes steps with content
- [ ] Not found → 404

### 1.7 DELETE /api/research/history/{sessionId} (Delete History Session)
- [ ] Happy path: non-running session → 204
- [ ] Conflict: PROCESSING session → 409
- [ ] Not found → 404

### 1.8 POST /api/research/history/bulk-delete (Bulk Delete)
- [ ] Happy path: valid IDs, all non-processing → 204
- [ ] Rollback: any processing ID → 409, no deletions performed
- [ ] Validation: empty/null body → 400

### 1.9 POST /api/research/{sessionId}/followup (Submit Follow-up)
- [ ] Happy path: completed session + valid question → 200 with LLM answer
- [ ] Validation: blank question → 400
- [ ] Error: non-completed session → 400

### 1.10 GET /api/research/{sessionId}/report (Get Report)
- [ ] Happy path: completed session with report → 200 with content
- [ ] Fallback: generate from steps when finalReport is null
- [ ] Error: not found or not completed → 400

### 1.11 GET /api/research/stream/{sessionId} (SSE Stream)
- [ ] Returns text/event-stream content type
- [ ] Register session for event delivery
- [ ] Clean up on emitter completion/error/timeout

## 2. Unit Test Requirements

### 2.1 ResearchOrchestratorService
Every public method SHALL have at least one test:
- `createAndStart()` — persistence, status, ID assignment
- `getResearch()` — found and not-found cases
- `cancelResearch()` — PROCESSING vs non-processing vs null session
- `deleteSession()` — normal delete, not found, processing rejection
- `deleteSessionsInBulk()` — all valid, mixed (some invalid), empty list
- `submitFollowUp()` — completed session, non-completed, null session
- `getHistoricalSessions()` — pagination
- `searchByTopic()` — case-insensitive match

### 2.2 ResearchStreamingService
- Emitter registration and retrieval
- Connection removal (known/unknown sessionId)
- Event sending with no connection (graceful warning)
- Timeout configuration via @Value injection
- Concurrent emitter isolation (different sessions, different emitters)

### 2.3 AbandonedSessionCleanupService
- `cleanupAbandonedSessions()` — marks stale PROCESSING sessions as CANCELLED
- Idempotency — running twice doesn't double-mark
- No-op when no abandoned sessions exist
- Scheduled execution verification (@Scheduled annotation present)

### 2.4 FollowUpService
- Happy path: returns LLM answer for completed session
- Throws IllegalArgumentException for non-existent session ID
- Prompt construction includes topic, report (truncated), and question

### 2.5 McpToolRouter
- Preferred server per task type
- Routing chain order verification
- Unknown task type → null/empty

## 3. Integration Test Requirements

### 3.1 Database Layer
- [ ] Sessions persist with all fields (topic, status, timestamps)
- [ ] Steps cascade-delete when session is deleted
- [ ] findByTopicContainingIgnoreCase works case-insensitively
- [ ] findAllByStatusAndCreatedAtBefore finds correct cutoff sessions

### 3.2 Transaction Boundaries
- [ ] `@Transactional(readOnly = true)` on read-only service methods
- [ ] Bulk delete runs in single transaction (all-or-nothing)

## 4. Security Requirements

### 4.1 Authentication & Authorization
- [ ] No unauthenticated access to research endpoints
- [ ] API key or JWT required for all `/api/**` routes
- [ ] Rate limiting on POST /api/research (prevent abuse)

### 4.2 Input Validation
- [ ] `topic` field: max length enforced (currently @NotBlank only)
- [ ] `question` in follow-up: max length enforced
- [ ] User-supplied content escaped before rendering

### 4.3 Secret Management
- [ ] No hardcoded API keys in source code defaults
- [ ] All secrets via environment variables with no fallback default containing real credentials

## 5. Configuration Requirements

### 5.1 Production Profile
- [ ] `application-prod.yml` exists (or equivalent profile-based config)
- [ ] Production datasource URL from env var
- [ ] Production LLM endpoint configurable

### 5.2 Observability
- [ ] Actuator endpoints available (/health, /info)
- [ ] Structured logging (MDC correlation IDs)
- [ ] Metrics for request count, error rate, response time

## 6. Performance Requirements

### 6.1 Query Performance
- [ ] No N+1 queries in history listing
- [ ] `findByIdWithSteps` uses JOIN FETCH (verified — no N+1)

### 6.2 Concurrency
- [ ] ConcurrentHashMap for SSE emitter storage (thread-safe)
- [ ] Async executor bounded (core=5, max=20, queue=100)

## 7. Acceptance Criteria

The application passes production validation when:
1. All unit tests pass
2. All integration tests pass
3. No Critical security issues remain
4. Configuration has no hardcoded secrets with real credentials
5. API contracts match frontend expectations
6. Database schema validation mode is functional
