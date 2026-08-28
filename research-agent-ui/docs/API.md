# Research Agent Frontend — API Reference

## Overview

The Angular frontend communicates with the backend via REST and SSE. All requests are proxied through `proxy.conf.json` in development, which maps `/api/*` to `http://localhost:8080`. In production, this proxy must be replaced with a real CORS setup or reverse proxy.

---

## API Client — ResearchService & ResearchHistoryService

The primary interfaces between Angular components and the backend are two services:

### `ResearchService` (singleton, providedIn: root) — Primary Communication Layer

#### REST API Calls (HttpClient Observable<T>)

```typescript
// All methods return Observable<T> for async handling via .subscribe() or RxJS operators

startResearch(request: ResearchStartRequest): Observable<ResearchSession>
getStatus(sessionId: string): Observable<ResearchSession>
cancelResearch(sessionId: string): Observable<void>
getHistoricalSession(sessionId: string): Observable<ResearchSession>
submitFollowUp(sessionId: string, question: string): Observable<string>
```

#### SSE Connection (EventSource)

```typescript
// Establishes a native EventSource connection for real-time progress events
connectSse(sessionId: string, streamUrl?: string): void

// Closes the active SSE connection and clears stall timer
disconnectSse(): void
```

The service listens for typed SSE events and updates reactive signals accordingly. Includes exponential backoff reconnection (3 attempts) before falling back to polling.

#### Polling Fallback (setInterval + HttpClient)

When the SSE connection fails or stalls, automatic fallback to polling:

```typescript
// Starts 5-second interval polling of /api/research/{sessionId}
startPolling(sessionId: string): void

// Clears the polling interval and stall timer
stopPolling(): void
```

On terminal state detection (COMPLETED/FAILED/CANCELLED), steps from backend response are synced into frontend signal list.

### `ResearchHistoryService` — History List & Deletion Operations

```typescript
// Paginated history loading with optional search term
loadHistory(page: number, searchTerm?: string): void

// Single session delete with 409 Conflict handling
deleteSession(sessionId: string): Observable<void>

// Bulk multi-select delete (all-or-nothing rollback)
bulkDeleteSessions(ids: string[]): Observable<void>
```

---

## Backend API Endpoints (Consumed by Frontend)

See [backend docs/API.md](../../research-agent-backend/docs/API.md) for complete backend API reference. The frontend consumes the following endpoints:

| Endpoint | Method | Purpose | SSE/Fallback |
|----------|--------|---------|--------------|
| `/api/research` | POST | Start new session | SSE (primary), polling (fallback on disconnect/stall) |
| `/api/research/{sessionId}` | GET | Get session status | Polling only |
| `/api/research/{sessionId}` | DELETE | Cancel session | None |
| `/api/research/history` | GET | Paginated history list | ResearchHistoryService |
| `/api/research/history/search?query=` | GET | Search by topic name | ResearchHistoryService (debounced) |
| `/api/research/history/{sessionId}` | GET | Get historical session detail with steps | HistoryDetailComponent |
| `/api/research/history/{sessionId}` | DELETE | Delete single historical session | Single delete (MatDialog confirm) |
| `/api/research/history/bulk-delete` | POST | Bulk delete selected sessions | Multi-select delete toolbar |
| `/api/research/stream/{sessionId}` | GET | Subscribe to SSE events | Primary streaming method with reconnection |
| `/api/research/{sessionId}/report` | GET | Get final report text | Fallback if REPORT_DONE not received |
| `/api/research/{sessionId}/followup` | POST | Submit follow-up question | FollowUpFormComponent |

---

## SSE Event Types (Frontend-Side)

The frontend defines typed interfaces for each SSE event type. These are used to cast the raw `Event.data` from the browser's native `EventSource` API:

| Type | Interface | Purpose |
|------|-----------|---------|
| `PROGRESS` | `ProgressSseEvent` — `{type: 'PROGRESS', sessionId?, payload: string}` | Step progress update (start/transition) |
| `CONTENT` | `ContentSseEvent` — `{type: 'CONTENT', sessionId?, payload: string}` | Streaming content chunk from sub-topic research |
| `REPORT_START` | `ReportStartSseEvent` — `{type: 'REPORT_START', sessionId?}` | Report synthesis started (injects "Generating Report" step) |
| `REPORT_CHUNK` | `ReportChunkSseEvent` — `{type: 'REPORT_CHUNK', sessionId?, payload: string}` | Report text chunk (accumulated into report content signal + resets stall timer) |
| `REPORT_DONE` | `ReportDoneSseEvent` — `{type: 'REPORT_DONE', sessionId?, payload: string}` | Report generation complete (sets session to COMPLETED) |
| `STEP_COMPLETE` | `StepCompleteSseEvent` — `{type: 'STEP_COMPLETE', sessionId?, payload: Record<string, unknown>}` | Step completion metadata (multi-strategy matching in handler) |
| `ERROR` | `ErrorSseEvent` — `{type: 'ERROR', sessionId?, payload: string}` | Error notification |

### SSE Event Handler Mapping

```typescript
// In ResearchService.connectSse(), event listeners are mapped by type name:
this.sseSource.addEventListener('PROGRESS',       (e) => this.handleProgress(e));
this.sseSource.addEventListener('STEP_COMPLETE',  (e) => this.handleStepComplete(e));
this.sseSource.addEventListener('CONTENT',        (e) => this.appendContent(data.payload));
this.sseSource.addEventListener('REPORT_CHUNK',   (e) => { /* accumulate + reset stall timer */ });
this.sseSource.addEventListener('REPORT_START',   (e) => { /* inject report step + start stall timer */ });
this.sseSource.addEventListener('REPORT_DONE',    (e) => this.onReportDone(e));
this.sseSource.addEventListener('ERROR',          (e) => this.onError(e));
```

### SSE Event Flow During a Research Session

1. **PROGRESS** — Updates step list: marks previous IN_PROGRESS as COMPLETED, sets current sub-topic to IN_PROGRESS; drives `progressPercent()` computed signal
2. **CONTENT** — Appends raw text chunks incrementally to `_reportContentSignal` for live preview during sub-topic research
3. **STEP_COMPLETE** — Marks corresponding step as COMPLETED via multi-strategy matching (string name, number index, or object key matching)
4. **REPORT_START** — Injects "Generating Report" IN_PROGRESS step; starts 90-second stall timer
5. **REPORT_CHUNK** (streaming) — Accumulates into `_reportContentSignal`; each chunk resets the stall timer
6. **REPORT_DONE** — Sets final report content, updates session status to 'COMPLETED', stops streaming
7. **ERROR** — Sends error via `error$` observable, sets session status to 'FAILED'

### Stall Timer Behavior

During Phase 3 (report generation), if no REPORT_CHUNK arrives within 90 seconds:
1. Stall timer fires → emits "Report generation stalled" message via `errorSubject`
2. Switches to polling fallback (`startPolling`)
3. Polling detects terminal state and syncs final steps/report from backend

---

## Data Models (Frontend Interfaces)

See [models.md](./MODELS.md) for complete frontend model definitions with all fields and types.
