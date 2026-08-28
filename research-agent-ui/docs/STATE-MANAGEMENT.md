# Research Agent Frontend — State Management

## Overview

The Angular frontend uses **Signal-based reactive state management** exclusively. There are no NgRx stores, BehaviorSubject patterns (beyond one RxJS Subject for errors), or Redux-style reducers. All application state is centralized in a single `ResearchService` singleton and exposed via Angular Signals. History-specific state is managed separately in `ResearchHistoryService`.

---

## Signal Architecture

### ResearchService — The Central State Manager

```
┌───────────────────────────────────────────────────────┐
│              ResearchService (Singleton)                │
│                                                         │
│  ┌───────────────── Mutable Signals ─────────────────┐ │
│  │                                                     │ │
│  │  researchSession    signal<ResearchSession|null>   │ │
│  │  _researchSteps     writable Signal<ResearchStep[]>│ │
│  │  _reportContent     writable Signal<string>        │ │
│  │  isStreaming        signal<boolean>                │ │
│  └─────────────────────────────────────────────────────┘ │
│                                                         │
│  ┌───────────────── Derived Signals ───────────────────┐│
│  │                                                     ││
│  │  progressPercent     computed<number>               ││
│  │  activeStepIndex     computed<number>               ││
│  │  shouldDisplayStreamingContent computed<boolean>    ││
│  └─────────────────────────────────────────────────────┘│
│                                                         │
│  ┌───────────────── Reactive Stream ───────────────────┐│
│  │                                                     ││
│  │  error$              Observable<string>             ││
│  └─────────────────────────────────────────────────────┘│
│                                                         │
│  ┌────────────── Procedural Side-Effects ──────────────┐│
│  │                                                     ││
│  │  sseSource: EventSource            (SSE connection) ││
│  │  stallTimer: number                (stall detection)││
│  │  pollingTimer: number              (polling timer)  ││
│  └─────────────────────────────────────────────────────┘│
└───────────────────────────────────────────────────────────┘
```

### Signal Categories Explained

#### Mutable Signals (Writable State)

These are the core state variables that components read from. They are updated internally by the service in response to SSE events, polling responses, and user actions.

- **`researchSession: signal<ResearchSession | null>`** — The current active session object. Set when starting a new research task, updated on every SSE event or polling response. `null` when no session is active (e.g., on the home page).

- **`_researchStepsSignal: writable Signal<ResearchStep[]>`** — Internal mutable step list. Updated by SSE event handlers (`handleProgress`, `handleStepComplete`). Private field with public readonly getter `researchSteps()`. Components read this via the getter, not the direct setter.

- **`_reportContentSignal: writable Signal<string>`** — Internal mutable report content accumulator. Appended to incrementally during streaming (CONTENT and REPORT_CHUNK events). Private field with public readonly getter `reportContent()`.

- **`isStreaming: signal<boolean>`** — Whether an active SSE connection or polling is running for the current session. Used by components to show/hide loading indicators.

#### Derived Signals (Readonly Computed State)

These signals automatically recalculate when their dependencies change. Components bind directly to them in templates without needing manual subscription management.

- **`progressPercent: computed<number>`** — The progress bar value, derived from the step list. Calculates `(completedSteps + sum(inProgressCount * 0.5)) / totalSteps * 100`. Gives smooth transitions because each in-progress step counts as half a completed step. Automatically recalculates whenever `researchSteps()` changes.

- **`activeStepIndex: computed<number>`** — The index of the currently active (IN_PROGRESS) step, derived from the step list. Returns -1 if no step is in progress. Components bind to this for rendering the highlighted step badge.

- **`shouldDisplayStreamingContent: computed<boolean>`** — True when session exists, status is PROCESSING, and finalReport has not been received yet. Used by components to show/hide live content preview before the full report is ready.

#### Reactive Stream

- **`error$: Observable<string>`** — An RxJS Subject that emits error messages. Used by components (e.g., ActiveResearchComponent) to display error notifications from SSE or other sources. The only RxJS observable in the service — all other state is Signal-based.

### ResearchHistoryService — Separate State for History List

```
ResearchHistoryService
│
├── sessions = signal<HistoryItem[]>([])        // Current page's session list
├── currentPage = signal<number>(0)             // Zero-based page index
├── totalPages = signal<number>(1)              // Total available pages
├── totalElements = signal<number>(0)           // Total sessions across all pages
└── searchTerm: string | undefined              // Current search filter (undefined = no filter)
```

History state is **not** part of the central ResearchService — it lives in its own service since history operations are independent of active research session management.

---

## Signal-Bound Component Rendering

Components bind directly to signals from `ResearchService` using Angular's signal binding syntax (`()`):

```html
<!-- In active-research.component.html -->
<step-list [steps]="researchSteps()" [activeStepIndex]="activeStepIndex()"></step-list>
<mat-progress-bar mode="determinate" [value]="progressPercent()"></mat-progress-bar>

@if (isStreaming()) {
  <mat-spinner></mat-spinner>
}

<!-- Status chip with color based on session status -->
@if (researchSession()?.status === 'COMPLETED') {
  <span class="chip completed">Completed</span>
} @else if (researchSession()?.status === 'PROCESSING') {
  <span class="chip processing">Processing</span>
}
```

### How Signal Binding Works

When a component binds to a signal in the template using `()`, Angular automatically subscribes to that signal's change notifications. The component re-renders only when the specific signal it depends on changes — not on every Angular change detection cycle (unlike with plain variables). This is called **fine-grained reactivity** and avoids unnecessary DOM updates.

For example, if a template binds `[value]="progressPercent()"`, the progress bar will update automatically whenever `researchSteps()` changes (which triggers recomputation of `progressPercent()`), but other parts of the component that don't depend on `progressPercent()` won't be affected by this change.

---

## SSE Event → Signal Mapping

### How SSE Events Update Signals

```
SSE Event               Signal Update                    Component Effect
────────────           ───────────────                    ───────────────
PROGRESS (step start)   _researchStepsSignal.update()      progressPercent() recalculates
                           // Previous step: COMPLETED      activeStepIndex() recalculates
                           // Current step: IN_PROGRESS
STEP_COMPLETE           _researchStepsSignal.update()      Same as above
REPORT_START            _researchStepsSignal.add()         New "Generating Report" step appears
                           (injects a new step)
CONTENT / REPORT_CHUNK  _reportContentSignal.update()      Live preview of report text
                           // Append to existing content
REPORT_DONE             researchSession.update()           Session status → COMPLETED
                           + reportContent update
ERROR                   error$.next()                     Error notification displayed
```

### Detailed Signal Update Logic

#### `handleProgress(event: ProgressSseEvent)` — Step Progress Update

When a new step begins (PROGRESS event):

1. If no steps exist yet, create the first dynamic "Researching Sub-topics" IN_PROGRESS step
2. Detect sub-topic completion messages ("Completed research on: X") to transition from one sub-topic step to the next
3. Otherwise, update existing IN_PROGRESS step's description or create a new IN_PROGRESS step for the current sub-topic

```typescript
// Pseudo-code of the signal update logic:
this._researchStepsSignal.update(steps => {
  // Mark previous in-progress step as completed
  const updated = steps.map(step => {
    if (step.status === 'IN_PROGRESS') {
      return { ...step, status: 'COMPLETED', description: null };
    }
    return step;
  });

  // Find or create the current IN_PROGRESS step
  // Only if there isn't already an IN_PROGRESS step (e.g., REPORT_START may have added it)
  const hasInProgress = steps.some(s => s.status === 'IN_PROGRESS');
  if (!hasInProgress) {
    return [...updated, { stepNumber: updated.length + 1, name: 'Researching X', status: 'IN_PROGRESS' }];
  }
  return updated;
});
```

#### `appendContent(content: string)` — Report Content Accumulation

When a streaming content event arrives (CONTENT or REPORT_CHUNK):

1. Read the current signal value via `_reportContentSignal()`
2. Append the new chunk to the existing content using signal's `update()` method
3. Return concatenated result as the new signal value

```typescript
// Pseudo-code of the append logic:
this._reportContentSignal.update(prev => prev + payload);
```

**Note:** This approach (reading and updating) is necessary because Angular Signals don't support in-place mutation. The `update()` method takes a callback that receives the previous value and returns the new one.

#### `onReportDone(event: ReportDoneSseEvent)` — Session Completion

When the final report generation completes (REPORT_DONE event):

1. Update `_reportContentSignal` to the complete report content from the event payload
2. Clear any pending stall timer
3. Mark all IN_PROGRESS steps as COMPLETED
4. Set `researchSession` status to 'COMPLETED'
5. Set `isStreaming` to false

---

## DestroyRef Cleanup Pattern

Angular's `DestroyRef` is used for cleanup of procedural side-effects (SSE connections, polling timers) when a component is destroyed — **no manual subscription management needed**. This is preferred over manual `.unsubscribe()` because:

1. It handles the case where a component navigates away before manually calling disconnect methods
2. It eliminates the need to track subscriptions in class fields and unsubscribe in `ngOnDestroy`
3. It's part of Angular's built-in lifecycle, not custom code

```typescript
export class ActiveResearchComponent implements OnInit, OnDestroy {
  private destroyRef = inject(DestroyRef);
  
  ngOnInit() {
    // SSE connection — cleaned up automatically when component destroyed
    this.destroyRef.onDestroy(() => {
      this.researchSession.disconnectSse();
    });

    // Polling interval — cleaned up automatically when component destroyed
    this.destroyRef.onDestroy(() => {
      this.researchSession.stopPolling();
    });
  }
}
```

### Why Not Use takeUntilDestroyed()?

The codebase uses `DestroyRef.onDestroy()` rather than RxJS's `takeUntilDestroyed()` operator because:

1. **More explicit**: The cleanup logic is clearly visible in the component code, not hidden inside an observable chain
2. **Works for non-observable resources**: SSE EventSource connections and setInterval timers are not Observables — they can't use `takeUntilDestroyed()` directly
3. **Simpler API**: No need to create a Subject or combine cleanup logic across multiple subscriptions

---

## Progress Calculation Detail

The progress bar uses a smooth transition approach with half-weight for in-progress steps:

```typescript
progressPercent = computed(() => {
  const steps = this.researchSteps();
  
  if (!steps.length) return 0;
  
  let completedCount = 0;
  let inProgressCount = 0;
  
  // Count completed and in-progress steps
  for (const step of steps) {
    if (step.status === 'COMPLETED') completedCount++;
    if (step.status === 'IN_PROGRESS') inProgressCount++;
  }
  
  // In-progress steps count as half a completed step for smooth transitions
  const progressValue = completedCount + (inProgressCount * 0.5);
  
  return Math.round((progressValue / steps.length) * 100);
});
```

**Example calculations:**
- All steps pending: `progressPercent = 0%`
- 2 of 5 completed, 1 in progress: `(2 + 1*0.5) / 5 * 100 = 50%` (smooth transition from 40% when only 2 were complete)
- All steps completed: `progressPercent = 100%`

---

## Signal vs Observable Decision Rationale

The project deliberately uses Signals over Observables for state management because:

### Why Signals?

| Criteria | Signals ✓ | Observables ✗ |
|----------|-----------|---------------|
| **Template binding** | `()` syntax, auto-subscription | Must use `async` pipe or manual subscription |
| **Fine-grained reactivity** | Updates only bound components | Triggers full change detection cycle |
| **Type safety** | Compile-time type checking via TS types | Runtime type narrowing required |
| **Performance** | No unnecessary DOM updates | Full component tree re-rendering |
| **Simplicity** | No subscription/unsubscription tracking | Must manage lifecycle to prevent memory leaks |

### Why Keep One Observable (error$)?

The `error$` observable exists because:

1. **Error propagation across components**: The service doesn't know which specific error notification component it needs to display — different components handle errors differently
2. **RxJS Subject pattern for multi-subscriber notifications**: Multiple components may need to listen for the same error events (e.g., a global snackbar + a local error banner)
3. **Composable with RxJS operators**: Errors can be piped through RxJS operators (`debounceTime`, `distinctUntilChanged`, etc.) if needed

---

## Signal Lifecycle in a Typical Session

```
1. User submits research topic on /research/new page
   → ResearchInputComponent calls researchSession.startResearch()
   
2. startResearch():
   - Creates new session via POST /api/research
   - Sets isStreaming = true
   - Sets researchSession to the returned session (status: PROCESSING)
   - Calls connectSse(sessionId) — establishes SSE connection
   
3. SSE connects, first PROGRESS event arrives:
   → _researchStepsSignal.update() adds "Researching Sub-topics" as IN_PROGRESS
   → progressPercent recalculates → ~10% (half of one step out of total)
   
4. Subsequent events during research:
   → Each PROGRESS/STEP_COMPLETE updates _researchStepsSignal
   → progressPercent and activeStepIndex recalculate automatically
   → CONTENT/REPORT_CHUNK append to _reportContentSignal incrementally
   
5. REPORT_START arrives (Phase 3 begins):
   → "Generating Report" step injected as IN_PROGRESS
   → Stall timer started (90s without chunks → polling fallback)
   
6. REPORT_DONE event arrives (session complete):
   → researchSession updated with COMPLETED status + finalReport content
   → isStreaming set to false
   → reportContent fully populated
   
7. User navigates away from the page:
   → DestroyRef.onDestroy() triggers disconnectSse() and stopPolling()
   → All signals are cleaned up, no memory leaks
```
