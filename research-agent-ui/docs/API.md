# Research Agent Frontend — API Reference

## Overview

The Angular frontend communicates with the backend via REST and SSE. All requests are proxied through `proxy.conf.json` in development, which maps `/api/*` to `http://localhost:8080`. In production, this proxy must be replaced with a real CORS setup or reverse proxy.

---

## API Client — ResearchService

The `ResearchService` (singleton, provided-in-root) is the primary interface between Angular components and the backend. It encapsulates three communication patterns:

### 1. REST API Calls (HttpClient Observable<T>)

```typescript
// All methods return Observable<T> for async handling via .subscribe() or RxJS operators

startResearch(request: ResearchStartRequest): Observable<ResearchSession>
getStatus(sessionId: string): Observable<ResearchSession>
cancelResearch(sessionId: string): Observable<void>
getHistoricalSession(sessionId: string): Observable<ResearchSession>
submitFollowUp(sessionId: string, question: string): Observable<FollowUpResponse>
```

### 2. SSE Connection (EventSource)

```typescript
// Establishes a native EventSource connection for real-time progress events
connectSse(sessionId: string, streamUrl?: string): void

// Closes the active SSE connection
disconnectSse(): void
```

The service listens for typed SSE events and updates reactive signals accordingly. See [state-management.md](./STATE-MANAGEMENT.md) for details on how SSE events map to signal state.

### 3. Polling Fallback (setInterval + HttpClient)

When the SSE connection fails (EventSource.readyState === CLOSED) and the session is not in a terminal state, automatic fallback to polling:

```typescript
// Starts 5-second interval polling of /api/research/{sessionId}
startPolling(sessionId: string): void

// Clears the polling interval
stopPolling(): void
```

---

## Backend API Endpoints (Consumed by Frontend)

See [backend docs/API.md](../research-agent-backend/docs/API.md) for complete backend API reference. The frontend consumes the following endpoints:

| Endpoint | Method | Purpose | SSE/Fallback |
|----------|--------|---------|--------------|
| `/api/research` | POST | Start new session | SSE (primary), polling (fallback) |
| `/api/research/{sessionId}` | GET | Get session status | Polling only |
| `/api/research/{sessionId}` | DELETE | Cancel session | None |
| `/api/research/history` | GET | Paginated history list | None |
| `/api/research/history/search` | GET | Search by topic name | None |
| `/api/research/{sessionId}/report` | GET | Get final report text | None |
| `/api/research/{sessionId}/followup` | POST | Submit follow-up question | None |
| `/api/research/stream/{sessionId}` | GET | Subscribe to SSE events | Primary streaming method |

---

## SSE Event Types (Frontend-Side)

The frontend defines typed interfaces for each SSE event type. These are used to cast the raw `Event.data` from the browser's native `EventSource` API:

| Type | Interface | Purpose |
|------|-----------|---------|
| `PROGRESS` | `ProgressSseEvent` — `{type: 'PROGRESS', sessionId?, payload: string}` | Step progress update |
| `CONTENT` | `ContentSseEvent` — `{type: 'CONTENT', sessionId?, payload: string}` | Streaming content chunk |
| `REPORT_START` | `ReportStartSseEvent` — `{type: 'REPORT_START', sessionId?}` | Report synthesis started |
| `REPORT_CHUNK` | `ReportChunkSseEvent` — `{type: 'REPORT_CHUNK', sessionId?, payload: string}` | Report text chunk |
| `REPORT_DONE` | `ReportDoneSseEvent` — `{type: 'REPORT_DONE', sessionId?, payload: string}` | Report generation complete |
| `STEP_COMPLETE` | `StepCompleteSseEvent` — `{type: 'STEP_COMPLETE', sessionId?, payload: Record<string, unknown>}` | Step completion metadata |
| `ERROR` | `ErrorSseEvent` — `{type: 'ERROR', sessionId?, payload: string}` | Error notification |

### SSE Event Handler Mapping

```typescript
// In ResearchService.connectSse(), event listeners are mapped by type name:
switch (event.type) {
  case 'PROGRESS':      this.handleProgress(event);     break;
  case 'STEP_COMPLETE': this.handleStepComplete(event); break;
  case 'CONTENT':       this.appendContent(event.payload); break;
  case 'REPORT_START':  /* triggers report step injection */ break;
  case 'REPORT_CHUNK':  this.appendContent(event.payload); break;
  case 'REPORT_DONE':   this.onReportDone(event);      break;
  case 'ERROR':         this.onError(event);           break;
}
```

### SSE Event Flow During a Research Session

1. **REPORT_START** — Triggers injection of "Generating Report" step into the steps list with IN_PROGRESS status
2. **PROGRESS** (repeated) — Updates previous steps to COMPLETED, current step to IN_PROGRESS with description; drives `progressPercent()` computed signal
3. **CONTENT** / **REPORT_CHUNK** (streaming) — Appends raw text chunks incrementally to `_reportContentSignal` for live preview
4. **STEP_COMPLETE** (on each step finish) — Marks corresponding step as COMPLETED by matching payload against step names via JSON.stringify + lowercase comparison (fragile approach)
5. **REPORT_DONE** — Sets final report content, updates session status to 'COMPLETED'
6. **ERROR** — Sends error via `error$` observable, sets session status to 'FAILED'

---

## Data Models (Frontend Interfaces)

See [models.md](./MODELS.md) for complete frontend model definitions with all fields and types.
