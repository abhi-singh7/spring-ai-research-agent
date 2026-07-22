## Context

The research agent UI receives SSE events from the backend during an active research session. The current implementation has a critical UX gap: when researching unknown/new topics, users see no progress feedback for potentially minutes — the step list stays empty, the content viewer never appears until completion, and the progress bar shows 0%. This happens because multiple bugs in the event handling pipeline silently drop or hide intermediate events.

### Root Causes Identified

1. **Step List Not Populated During Streaming** (CRITICAL) — `handleProgress()` has a guard `if (!steps.length) return;` that drops ALL progress events when no steps exist yet. Steps are only created via `REPORT_START`, which fires after all sub-topic research completes. For unknown topics with many sub-topics, this gap can be several minutes.

2. **CONTENT/REPORT_CHUNK Events Never Displayed During Streaming** (CRITICAL) — Both event types accumulate content in `_reportContentSignal` via `appendContent()`, but the `<report-viewer>` component only renders when `researchSession().status === 'COMPLETED' && researchSession().finalReport`. During streaming, status is `PROCESSING` and `finalReport` is null.

3. **Progress Bar Shows 0%** — `progressPercent()` returns 0 when `_researchStepsSignal` is empty (same root cause as #1).

4. **Fragile Step Completion Matching** — `handleStepComplete()` uses `JSON.stringify(event.payload)` for substring matching against step names, which fails for structured payloads and can never match correctly.

5. **Silent Error Swallowing** — `loadSession()` error handler is empty: `error: () => {}`.

## Goals / Non-Goals

**Goals:**
- Display a step list during sub-topic research phase with dynamic "Researching Sub-topics" step created from PROGRESS events
- Show streaming content as it arrives during active research via `<report-viewer>` component, before the final report is completed
- Fix progress bar to show meaningful percentage during both phases (sub-topic research + report generation)
- Fix step completion matching for reliable COMPLETED state transitions
- Surface session loading errors to users

**Non-Goals:**
- Backend changes — all fixes are frontend-only
- Changing SSE event format or introducing new backend events
- Implementing real-time sub-topic breakdown (the LLM creates these on the backend; we only display what's available)

## Decisions

### Decision 1: Dynamic Step Creation from PROGRESS Events During Sub-Topic Research

**Choice:** Remove the empty-steps guard in `handleProgress()` and create a dynamic step when no steps exist yet.

**Rationale:** PROGRESS events already contain useful information (the sub-topic being researched). Instead of dropping them, we use the first PROGRESS event to create an initial "Researching Sub-topics" step with the description from the event payload. This gives users immediate visual feedback.

**Alternatives considered:**
- Create steps eagerly when `createAndStart()` is called — but we don't know sub-topic count until backend response, and creating placeholder steps would be misleading.
- Wait for REPORT_START to populate all steps — this is what currently happens, which causes the UX gap.

### Decision 2: Expose Streaming Content as Read-Only Signal

**Choice:** Make `_reportContentSignal` readable during active research by exposing it via a new `streamingContent` signal and conditionally rendering `<report-viewer>` when `isStreaming() && !researchSession()?.finalReport`.

**Rationale:** The CONTENT and REPORT_CHUNK events are already being accumulated correctly in `_reportContentSignal`. We just need to surface this content to the UI. Using a computed signal avoids duplicating the accumulation logic.

**Alternatives considered:**
- Create a separate `streamingContent` signal — adds complexity with two signals that could diverge.
- Always show `<report-viewer>` during streaming — but we already have it showing after completion; reusing the same component is cleaner.

### Decision 3: Fix Step Completion with Structured Payload Matching

**Choice:** Replace `JSON.stringify(event.payload)` substring matching with a direct step identifier from the event payload.

**Rationale:** The current approach of converting payloads to JSON strings and doing substring matching is fundamentally broken for structured data. Instead, we need the backend to include a reliable identifier (step name or index) in the STEP_COMPLETE event payload. Since this is a frontend-only change, we implement a more robust matching strategy: if `event.payload` contains a string, use it directly; if it's an object with a recognizable key like `stepName`, extract that; otherwise fall back to step index tracking.

**Alternatives considered:**
- Request backend changes — rejected (non-goal).
- Track steps by order/index rather than name — less user-friendly but more robust for frontend-only fix.

### Decision 4: Conditional Report Viewer Display with Signal-Based Approach

**Choice:** Use a computed signal `showStreamingContent` that returns true when `isStreaming() && !researchSession()?.finalReport`, and conditionally render `<report-viewer>` based on this signal.

**Rationale:** Computed signals in Angular provide reactive updates without manual subscription management. The display logic is clean: show streaming content while active, show final report when complete.

**Alternatives considered:**
- Use `ngIf` with multiple conditions inline — less readable, harder to maintain.
- Create a separate component for streaming content — overkill; reusing `<report-viewer>` avoids duplication.

## Risks / Trade-offs

1. **Race condition between SSE connection and step creation** — When the first PROGRESS event arrives before REPORT_START, we create a dynamic step. If REPORT_START later adds more steps (like "Generating Report"), there could be duplicate or overlapping step states. Mitigation: The `REPORT_START` handler already has a check for existing "Generating Report" step; we add similar dedup logic in the dynamic step creation path.

2. **Streaming content signal divergence** — If `_reportContentSignal` is updated by both CONTENT/REPORT_CHUNK handlers and then also set via `REPORT_DONE`, there could be double-content. Mitigation: `REPORT_DONE` sets the signal with `.set()` (not `.update()`), overwriting any accumulated streaming content with the authoritative final report content from the backend.

3. **Step completion matching edge cases** — The fragile string matching fix may still have edge cases for unusual step names or payloads. Mitigation: Document the expected payload format and add a TODO comment noting this as a future improvement if backend changes are needed later.

4. **Signal update performance during high-frequency streaming** — Multiple rapid CONTENT events could cause excessive signal updates. Mitigation: Angular Signals batch updates within the same microtask, so this is not a concern in practice.
