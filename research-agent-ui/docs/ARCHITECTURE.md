# Research Agent Frontend — Architecture

## Overview

The Angular frontend is a single-page application that provides real-time visualization of research sessions, including step-by-step progress tracking and streaming report display. It communicates with the backend via REST API (for status queries) and SSE (for real-time progress updates), with automatic fallback to polling on connection failure.

## System Architecture

```
┌───────────────────────────────────────────┐
│            Angular SPA                      │
│                                           │
│  ┌──────────────┐   ┌──────────────────┐  │
│  │ Feature       │   │ Shared           │  │
│  │ Components    │◄──│ Components       │  │
│  │               │   │                  │  │
│  │ • active-     │   │ • step-list      │  │
│  │   research    │   │ • report-viewer  │  │
│  │ • history-    │   │ • followup-form  │  │
│  │   detail      │   └──────────────────┘  │
│  │ • research-   │                         │
│  │   input       │                         │
│  │ • research-   │                         │
│  │   history     │                         │
│  └───────┬───────┘                         │
│          │                                  │
│  ┌───────▼─────────────────────────────┐    │
│  │ ResearchService (Singleton,           │    │
│  │ providedIn: root)                     │    │
│  │ - Signals + Computed                 │    │
│  │ - SSE connection with reconnection   │    │
│  │ - Polling fallback                   │    │
│  └──────────────┬─────────────────────┘    │
│                 │                           │
│  ┌──────────────▼─────────────────────┐    │
│  │ HttpClient (RxJS Observable<T>)     │    │
│  │ EventSource (SSE)                   │    │
│  │ setInterval (polling fallback)      │    │
│  └─────────────────────────────────────┘    │
└──────────────────┬──────────────────────────┘
                    │ proxy.conf.json: /api → localhost:8080
            ┌───────▼───────┐
            │ Spring Boot   │
            │ Backend       │
            └───────────────┘
```

## Component Architecture

### Feature Components (Standalone, Lazy-Loaded)

#### `ActiveResearchComponent` — Running Research Session View

The primary view for an active research session. Displays:
1. Back button to `/research/history`
2. Status header card with topic title and color-coded status chip
3. Determinate progress bar (only visible while streaming), bound to computed `progressPercent()` signal
4. `<step-list>` component bound to signals from `ResearchService`
5. `<report-viewer>` component — shown only when session is COMPLETED and has a final report
6. Cancel button during active research

**Key logic:**
- Extracts `sessionId` from route params via `inject(ActivatedRoute)`
- On init: calls `researchSession.startResearch()` which triggers SSE connection or polling fallback
- Progress bar value is bound to the computed signal `progressPercent()` — gives smooth transitions with half-weight for in-progress step

#### `ResearchInputComponent` — New Research Form

Form component for submitting a new research topic. Contains:
1. Reactive form (ReactiveFormsModule) with three fields:
   - `topic` (required, max 1024 chars)
   - `maxIterations` (optional number 1-50)
   - `subTopicCount` (optional number 1-20)
2. Submit button — disabled when form invalid or loading
3. Quick topic chips ("AI & Software Engineering", "Quantum Computing") that pre-fill the topic field

**Key logic:**
- On submit: calls `researchSession.startResearch()` with form values as `ResearchStartRequest`
- After session creation, navigates to `/research/{sessionId}` — SSE connection is initiated inside ResearchService

#### `ResearchHistoryComponent` — Research History List (Fully Implemented)

Paginated list view of all research sessions. Fully connected to the backend via `ResearchHistoryService`. Contains:
1. Search input that triggers debounced API search via `loadHistory(page, searchTerm)`
2. Session cards showing topic, status chip, and date
3. Pagination controls (Previous/Next buttons)
4. Selection mode with checkboxes for bulk multi-select delete
5. Single-delete per item with `MatDialog` confirmation dialog
6. Bulk-delete action bar with Select All / Deselect All

**Key logic:**
- Uses `ResearchHistoryService.sessions` signal for reactive data binding
- Selection state managed via local `Set<string>` of selected session IDs, tracked by computed signals (`selectionCount()`, `hasSelection()`)
- Single delete opens `DeleteConfirmationDialogComponent` showing topic name; confirms before calling `historyService.deleteSession()`
- Bulk delete collects all selected IDs and calls `historyService.bulkDeleteSessions(ids)` with rollback on conflict (409)

#### `HistoryDetailComponent` — Historical Session Detail View

Detailed view for a completed, failed, or cancelled session. Contains:
1. Back button to `/research/history`
2. Session info card with topic + status chip
3. `<step-list>` showing historical steps from the detail endpoint response
4. `<report-viewer>` if report exists
5. `<followup-form>` component — shown only for COMPLETED sessions (for asking questions about past research)

**Key logic:**
- Fetches session via `ResearchHistoryService.getHistoricalSession()` which calls `/api/research/history/{sessionId}`
- Maps backend step data to frontend `ResearchStep[]` format using `getStepName()` for display names
- Follow-up form posts question to `/api/research/{sessionId}/followup` and displays the answer

### Shared Components (Standalone, Reusable)

#### `StepListComponent` (`<step-list>`)

Reordered list of research steps with status indicators. Displays:
1. Step number in circular badge (gray by default, colored when active)
2. Status icon based on step state:
   - COMPLETED → checkmark (✓) — green
   - IN_PROGRESS → spinning arrow (↻) — animated via CSS `@keyframes spin`
   - FAILED → cross (✗) — red
   - PENDING → hollow circle (○) — gray, 50% opacity

**Inputs:**
- `steps = input.required<any[]>()` — required array of step objects
- `activeStepIndex = input(-1)` — index of the currently active step (-1 means none)

#### `ReportViewerComponent` (`<report-viewer>`)

Displays research report content in a card with header. Contains:
1. `<mat-card-title>` — component title (default: "Research Report")
2. `<mat-card-subtitle>` — optional subtitle from the `title` input
3. Raw text display wrapped in `<pre class="raw-content">` for formatting preservation

**Inputs:**
- `content = input.required<string>()` — required report content string
- `title = input('Research Report')` — optional title with default value

#### `FollowUpFormComponent` (`<followup-form>`)

Inline form for submitting follow-up questions about past research. Two display modes:
1. **Answer mode** (when an answer exists): Shows the LLM's answer in a styled `<pre>` block
2. **Question mode** (no answer yet): Form with textarea + submit button, disabled while loading

**Inputs:**
- `sessionId = input.required<string>()` — required session ID for the follow-up API call

**Key logic:**
- On submit: calls `researchSession.submitFollowUp(sessionId(), question)` which returns an Observable
- After receiving answer, updates local state to display mode 1 (answer mode)

---

## State Management — Signal-Based Architecture

The application uses Angular's **Signal-based reactive state management** exclusively — no NgRx, no BehaviorSubject patterns (beyond one RxJS Subject). The entire state is centralized in a single `ResearchService` singleton.

### Signal Structure

```
ResearchService (Singleton, providedIn: root)
│
├── Mutable State Signals (writable):
│   ├── researchSession — signal<ResearchSession | null>
│   │       Tracks the current active session; set when starting a new session or on SSE updates
│   │
│   ├── _researchStepsSignal — writable Signal<ResearchStep[]>
│   │       Internal mutable step list; updated by SSE event handlers (PROGRESS, STEP_COMPLETE)
│   │       Private field with public readonly getter researchSteps()
│   │
│   ├── _reportContentSignal — writable Signal<string>
│   │       Internal mutable report content accumulator; appended via appendContent() on CONTENT/REPORT_CHUNK events
│   │       Private field with public readonly getter reportContent()
│   │
│   └── isStreaming — signal<boolean>
│           True when SSE connection or polling is active for a session
│
├── Derived State Signals (readonly via asReadonly()):
│   ├── progressPercent — computed<number>
│   │       (completedSteps + (hasInProgress ? 0.5 : 0)) / totalSteps * 100
│   │       Gives smooth transitions: in-progress step counts as half a completed step
│   │
│   ├── activeStepIndex — computed<number>
│   │           Index of the step where status === 'IN_PROGRESS', -1 if none found
│   │
│   └── shouldDisplayStreamingContent — computed<boolean>
│              True when session is PROCESSING and no finalReport yet received (shows live content preview)
│
├── Reactive Stream:
│   └── error$ — Observable<string>
│           Error notifications for components to subscribe to via .subscribe()
│           Used by ActiveResearchComponent to display error messages
│
└── Procedural Side-Effects (not reactive signals):
    ├── EventSource connection + event listeners (SSE)
    │   Created in connectSse(), stored as class field, closed in disconnectSse()
    │   Cleanup via inject(DestroyRef).destroy() when component is destroyed
    │
    └── setInterval polling fallback
        Created in startPolling(), cleared in stopPolling()
        Also cleaned up via DestroyRef
```

### Computed Signal Details

**`progressPercent()`** — The progress bar value:
- Counts steps where status === 'COMPLETED' (full weight)
- Adds 0.5 for each step with status === 'IN_PROGRESS' (half-weight per in-progress step for smooth transition)
- Divides by total steps and multiplies by 100

Example: If there are 5 steps, with 3 completed and 1 in-progress:
```
progressPercent = (3 + 1*0.5) / 5 * 100 = 70%
```

**`activeStepIndex()`** — The currently active step index for rendering the progress indicator:
- Iterates through steps array to find where status === 'IN_PROGRESS'
- Returns -1 if no in-progress step exists (all completed or all pending)

### Signal-Bound Component Rendering

Components bind directly to signals from `ResearchService` using the `()` syntax:

```html
<!-- In active-research.component.html -->
<step-list [steps]="researchSteps()" [activeStepIndex]="activeStepIndex()"></step-list>
<mat-progress-bar mode="determinate" [value]="progressPercent()"></mat-progress-bar>

@if (isStreaming()) {
  <mat-spinner></mat-spinner>
}
```

Components inject the service and subscribe to the error observable:
```typescript
export class ActiveResearchComponent implements OnInit, OnDestroy {
  private researchSession = inject(ResearchService);
  
  ngOnInit() {
    this.researchSession.connectSse(sessionId());
    
    // Subscribe to errors from SSE or any component
    this.errorSub = this.researchSession.error$.subscribe(error => {
      // Display error message
    });
  }
}
```

### DestroyRef Cleanup Pattern

Angular's `DestroyRef` is used for cleanup without manual subscription management:

```typescript
export class ActiveResearchComponent implements OnInit, OnDestroy {
  private destroyRef = inject(DestroyRef);
  
  ngOnInit() {
    // SSE connection — cleaned up when component destroyed
    this.destroyRef.onDestroy(() => {
      this.researchSession.disconnectSse();
    });
    
    // Polling interval — cleaned up when component destroyed
    this.pollingCleanup = this.destroyRef.onDestroy(() => {
      this.researchSession.stopPolling();
    });
  }
}
```

---

## Routing Architecture

All routes use **lazy loading** via dynamic `import()` — no feature modules, only standalone components loaded on demand. This reduces initial bundle size by deferring component compilation until the route is first visited.

### Route Configuration (`app.routes.ts`)

| Path | Lazy-Loaded Component | Route Param | Description |
|------|----------------------|-------------|-------------|
| `` (empty) | — | — | Redirect to `/research/new` |
| `research/new` | `ResearchInputComponent` | — | New research form page |
| `research/:sessionId` | `ActiveResearchComponent` | `sessionId: string` | Active/running session view |
| `research/history` | `ResearchHistoryComponent` | — | History list with search + bulk delete |
| `research/history/:sessionId` | `HistoryDetailComponent` | `sessionId: string` | Historical session detail + follow-up |
| `**` (catch-all) | — | — | Redirect to `/research/new` |

### Navigation Pattern

The toolbar uses `routerLink` and `routerLinkActive="active-link"` for visual active state on links. The active link gets the `.active-link` CSS class applied automatically, which changes the text color and border-bottom styling.

```html
<mat-toolbar-row>
  <a routerLink="/research/new" routerLinkActive="active-link">New Research</a>
  <span class="spacer"></span>
  <a routerLink="/research/history" routerLinkActive="active-link">History</a>
</mat-toolbar-row>
```

---

## SSE Integration Strategy

### Primary: EventSource API (Native Browser SSE) with Reconnection

The service uses the browser's native `EventSource` API for SSE connections. It includes robust reconnection logic with exponential backoff and a stall timer for report generation detection.

```typescript
// In ResearchService.connectSse()
this.sseSource = new EventSource(`/api/research/stream/${sessionId}`);

// Listen for typed events by name (EventSource sends event names as the first field)
this.sseSource.addEventListener('PROGRESS', (event: ProgressSseEvent) => { ... });
this.sseSource.addEventListener('REPORT_CHUNK', (event: ReportChunkSseEvent) => {
  this._reportContentSignal.update(content => content + data.payload);
  // Reset stall timer on every chunk arrival
  this.startStallTimer(sessionId);
});

// Exponential backoff reconnection — max 3 attempts before polling fallback
this.sseSource.addEventListener('error', () => {
  if (reconnectAttempts < maxReconnectAttempts) {
    const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 8000); // 1s, 2s, 4s, cap at 8s
    setTimeout(() => this.connectSse(sessionId), delay);
    reconnectAttempts++;
  } else {
    this.startPolling(sessionId);
  }
});
```

### Stall Timer (90 seconds)

If no `REPORT_CHUNK` arrives within 90 seconds of starting report generation, the service forces a transition to polling fallback. This handles cases where the LLM produces output but the SSE connection becomes unresponsive:

```typescript
private startStallTimer(sessionId: string): void {
  this.stallTimer = setTimeout(() => {
    console.warn('[ResearchService] Report generation stalled — forcing completion via polling.');
    this.errorSubject.next('Report generation is taking longer than expected...');
    this.startPolling(sessionId);
  }, 90000);
}
```

### Fallback: Polling via HttpClient (5-second interval)

When SSE fails or stalls, the service automatically starts polling the `/api/research/{sessionId}` endpoint every 5 seconds. It stops when it detects a terminal state (COMPLETED, FAILED, or CANCELLED). On reaching a terminal state, steps from the backend response are synced into the frontend's step signal list:

```typescript
startPolling(sessionId: string): void {
  this.pollingTimer = setInterval(() => this.pollStatus(sessionId), 5000);
}

private pollStatus(sessionId: string): void {
  this.getStatus(sessionId).subscribe(session => {
    if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(session.status)) {
      // Sync steps from backend response into frontend signal
      const newSteps = session.steps?.map((step, i) => ({
        stepNumber: step.orderIndex + 1,
        name: this.getStepName(step.type),
        status: step.status,
        description: step.content || ''
      })) ?? [];
      if (newSteps.length > 0) {
        this._researchStepsSignal.set(newSteps);
      }
      if (session.finalReport) {
        this._reportContentSignal.set(session.finalReport);
      }
      this.stopPolling();
    }
    this.researchSession.set(session);
  });
}
```

### Cleanup on Destroy

Both SSE connection and polling interval are cleaned up via `DestroyRef`:

```typescript
// In ResearchService.disconnectSse() — manual cleanup (e.g., user navigates away)
this.clearStallTimer();
if (this.sseSource) {
  this.sseSource.close();
  this.sseSource = null;
}
// stopPolling() also clears the stall timer and polling interval
```

---

## Configuration Files

### `proxy.conf.json` — Development Proxy

Maps `/api/*` requests to the Spring Boot backend during development. This is necessary because the Angular dev server runs on port 4200 while the backend runs on port 8080, and CORS would block direct cross-origin requests.

```json
{
  "/api": {
    "target": "http://localhost:8080",
    "secure": false,
    "changeOrigin": true,
    "logLevel": "debug"
  }
}
```

**Usage:** `ng serve --proxy-config proxy.conf.json`

In production, this proxy must be replaced with either:
- A reverse proxy (nginx, Apache) that routes `/api/*` to the backend
- CORS configuration on the backend allowing the frontend origin

### `environments/` — Environment-Specific Configuration

| File | production | apiUrl | Purpose |
|------|-----------|--------|---------|
| `environment.ts` | false | '' (empty) | Dev environment — proxy handles /api routing |
| `environment.prod.ts` | true | '' (empty) | Production build — needs to be updated for deployment |

**Note:** The `apiUrl` is empty in both environments because the dev server proxy handles `/api` routing. In production, this should be set to the backend base URL (e.g., `https://api.example.com`).

### `tsconfig.json` — TypeScript Configuration

- **Strict mode**: All strict checks enabled
- **noImplicitOverride**: Must use `override` keyword when overriding methods/properties
- **strictTemplates**: Enables Angular template type checking for component bindings and pipes

---

## Build Configuration (`angular.json`)

- **Project name**: `research-agent-ui`
- **Component prefix**: `app` — all component selectors prefixed with `app-` (e.g., `<app-root>`, `<app-research-input>`)
- **Build output path**: `dist/research-agent-ui/`
- **Browser entry point**: `src/main.ts`
- **Default build**: Production mode with budget warning at 500kb for initial bundle

### Initial Bundle Budgets

| Type | Size Limit | Warning Threshold |
|------|-----------|-------------------|
| Initial Bundle (all chunks) | 2 MB | 1.5 MB |
| All Initial Bundles | 1.7 MB | 1 MB |
| Any Single Chunk | — | 400 KB |

### Lazy Loaded Chunks

The app splits into the following lazy-loaded chunk groups:
- `active-research-component` (14.55 kB) — Active session view + step-list + report-viewer
- `history-detail-component` (11.09 kB) — History detail + report-viewer + followup-form
- `research-input-component` (7.15 kB) — Research form with chips and validation
- `research-history-component` (5.65 kB) — History list view

### Shared Chunk

- `chunk-C4KO2HLL.js` (614 bytes) — Common runtime code shared between chunks

---

## Dependencies Summary

| Package | Version | Purpose |
|---------|---------|---------|
| @angular/animations ^18.0.0 | Required for Material animations |
| @angular/cdk ^18.0.0 | Component Dev Kit (Material foundation) |
| @angular/common ^18.0.0 | Common module (HTTP, Router, etc.) |
| @angular/compiler ^18.0.0 | Template compiler |
| @angular/core ^18.0.0 | Core Angular framework |
| @angular/forms ^18.0.0 | Reactive forms support |
| @angular/material ^18.0.0 | Material Design component library (M3 theming) |
| @angular/platform-browser ^18.0.0 | Browser platform bootstrap |
| @angular/platform-browser-dynamic ^18.0.0 | JIT compilation + runtime bootstrap |
| @angular/router ^18.0.0 | Client-side routing with lazy loading |
| ngx-markdown ^18.0.0 | Markdown rendering library (imported in app.config.ts) |
| rxjs ~7.8.0 | Reactive extensions for Angular |
| tslib ^2.3.0 | TypeScript helper functions |
| zone.js ~0.14.0 | Change detection zone polyfill |

---

## Services

### `ResearchService` (Primary — singleton, providedIn: root)

Handles all research session communication:
- **REST calls**: `startResearch()`, `getStatus()`, `cancelResearch()`, `submitFollowUp()`
- **SSE management**: `connectSse()`, `disconnectSse()` with exponential backoff reconnection
- **Polling fallback**: `startPolling()`, `stopPolling()`, `pollStatus()` (5-second interval)
- **Stall detection**: 90s timer during report generation → forces polling if chunks stop arriving
- **Signal state management**: All research session state flows through Angular signals

### `ResearchHistoryService` (Pagination + History queries)

Separate service for history-specific operations:
- **Paginated loading**: `loadHistory(page, searchTerm?)` — fetches `/api/research/history` or search endpoint
- **Session deletion**: `deleteSession(id)` — calls DELETE `/api/research/history/{sessionId}` with 409 handling
- **Bulk deletion**: `bulkDeleteSessions(ids)` — calls POST `/api/research/history/bulk-delete` with all-or-nothing rollback

---

## Known Implementation Notes

Several frontend features that were previously stubs are now fully implemented:

1. **ResearchHistoryComponent** — Fully connected to backend via ResearchHistoryService; includes pagination, search, single delete (MatDialog), and bulk multi-select delete
2. **HistoryDetailComponent** — Fetches session from `/api/research/history/{sessionId}` endpoint with steps mapped to frontend format
3. **SSE resilience** — Exponential backoff reconnection (max 3 attempts) + stall timer (90s) for report generation
4. **Step name mapping** — Backend step types (BREAKDOWN, SUBTOPIC, SEARCH, READ, SYNTHESIS, FINAL_REPORT) are mapped to human-readable names via `getStepName()`
