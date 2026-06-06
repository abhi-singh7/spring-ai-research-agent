# Research Agent Frontend — Architecture

## Overview

The Angular frontend is a single-page application that provides real-time visualization of research sessions, including step-by-step progress tracking and streaming report display. It communicates with the backend via REST API (for status queries) and SSE (for real-time progress updates).

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
│  │ - SSE connection management          │    │
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
3. Determinate progress bar (only visible while streaming)
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
- After session creation, navigates to `/research/{sessionId}` — **Note:** The current implementation has a placeholder stub (`setTimeout`) instead of actual API call

#### `ResearchHistoryComponent` — Research History List

Paginated list view of all research sessions. Contains:
1. Search input with `onSearch()` callback (stubbed)
2. Session cards showing topic, status chip, and date
3. Pagination controls (placeholder, not implemented)

**Key logic:**
- **Note:** The current implementation uses hardcoded placeholder data instead of API calls — this is marked as TODO

#### `HistoryDetailComponent` — Historical Session Detail View

Detailed view for a completed or failed session. Contains:
1. Back button to `/research/history`
2. Session info card with topic + status chip
3. `<report-viewer>` if report exists
4. `<followup-form>` component — shown only for COMPLETED sessions (for asking questions about past research)

**Key logic:**
- **Note:** The current implementation has a placeholder null value for `researchSession` and stubbed navigation — needs ResearchService integration

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
- After receiving answer, updates local state to display mode 1

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
│   └── activeStepIndex — computed<number>
│           Index of the step where status === 'IN_PROGRESS', -1 if none found
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
- Adds 0.5 if any step has status === 'IN_PROGRESS' (half-weight for smooth transition)
- Divides by total steps and multiplies by 100

Example: If there are 5 steps, with 3 completed and 1 in-progress:
```
progressPercent = (3 + 0.5) / 5 * 100 = 70%
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
  
  ngOnDestroy() {
    this.errorSub?.unsubscribe();
    this.researchSession.disconnectSse();
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
    this.sseSub = this.destroyRef.onDestroy(() => {
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
| `research/history` | `ResearchHistoryComponent` | — | History list with search |
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

### Primary: EventSource API (Native Browser SSE)

The service uses the browser's native `EventSource` API for SSE connections — no additional library needed. This is preferred over RxJS-based SSE because it handles reconnection automatically and requires zero configuration.

```typescript
// In ResearchService.connectSse()
this.sse = new EventSource(`/api/research/stream/${sessionId}`);

// Listen for typed events by name (EventSource sends event names as the first field)
this.sse.addEventListener('PROGRESS', (event: ProgressSseEvent) => {
  this.handleProgress(event);
});

// ... repeat for each event type ...

// Handle connection closure — triggers polling fallback if session is not terminal
this.sse.onclose = () => {
  const sessionStatus = this.researchSession()?.status;
  // If session is still PROCESSING, SSE disconnected unexpectedly → switch to polling
  if (sessionStatus === 'PROCESSING') {
    this.startPolling(sessionId);
  }
};

// Handle connection error — same fallback logic
this.sse.onerror = () => {
  const sessionStatus = this.researchSession()?.status;
  if (sessionStatus === 'PROCESSING') {
    this.startPolling(sessionId);
  }
};
```

### Fallback: Polling via HttpClient (5-second interval)

When SSE fails, the service automatically starts polling the `/api/research/{sessionId}` endpoint every 5 seconds. It stops polling when it detects a terminal state (COMPLETED, FAILED, or CANCELLED).

```typescript
// In ResearchService.startPolling()
this.pollingInterval = setInterval(() => {
  this.getStatus(sessionId).subscribe(session => {
    if (session.status === 'COMPLETED' || session.status === 'FAILED' || 
        session.status === 'CANCELLED') {
      clearInterval(this.pollingInterval);
      this.stopPolling();
    }
    // Update local state with polled data
    this.researchSession.update(session.id, session);
  });
}, 5000);
```

### Cleanup on Destroy

Both SSE connection and polling interval are cleaned up via `DestroyRef`:

```typescript
// In ResearchService.disconnectSse() — manual cleanup (e.g., user navigates away)
this.sse?.close();
this.stopPolling();
```

---

## Configuration Files

### proxy.conf.json — Development Proxy

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

### environments/ — Environment-Specific Configuration

| File | production | apiUrl | Purpose |
|------|-----------|--------|---------|
| `environment.ts` | false | '' (empty) | Dev environment — proxy handles /api routing |
| `environment.prod.ts` | true | '' (empty) | Production build — needs to be updated for deployment |

**Note:** The `apiUrl` is empty in both environments because the dev server proxy handles `/api` routing. In production, this should be set to the backend base URL (e.g., `https://api.example.com`).

### tsconfig.json — TypeScript Configuration

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
- **Dev server proxy config**: References `proxy.conf.json`

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

## Known TODO Items / Incomplete Implementations

Several components have placeholder/stubbed implementations that need to be connected to the ResearchService:

1. **ResearchInputComponent.onSubmit()** — Currently has a hardcoded `setTimeout` instead of actual API call
2. **ActiveResearchComponent.ngOnInit()** — Needs to fetch session data and connect SSE stream from the service
3. **ActiveResearchComponent.onCancel()** — Stubbed, no implementation for cancel button
4. **HistoryDetailComponent.researchSession** — Placeholder null value, needs ResearchService integration
5. **HistoryDetailComponent.goBack()** — Stubbed, no navigation logic back to history list
6. **ResearchHistoryComponent.sessions** — Hardcoded placeholder data instead of API fetch via ResearchService
7. **FollowUpFormComponent.onAsk()** — Placeholder answer with setTimeout instead of actual API call

These TODOs indicate the frontend is in an early but well-structured phase, using modern Angular 18 patterns (Signals, standalone components, lazy loading, input() API) and Material Design for the UI layer.
