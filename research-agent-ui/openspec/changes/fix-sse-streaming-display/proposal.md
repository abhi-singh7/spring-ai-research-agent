## Why

When users research unknown/new topics, SSE streaming responses from the backend are not displayed properly during the active research phase. The UI shows a blank state with no progress indicator until the entire research completes and the final report is available. This happens because of multiple cascading issues: step list events are silently dropped when no steps exist yet, intermediate CONTENT/REPORT_CHUNK events accumulate in an unused signal without being displayed to the user, and there's no visual feedback during the sub-topic research phase that can last minutes for unknown topics with many sub-topics.

## What Changes

- Remove the empty-steps guard in `handleProgress()` so PROGRESS events from sub-topic research create a dynamic "Researching Sub-topics" step instead of being silently dropped
- Display streaming content during active research by exposing `_reportContentSignal` as a read-only signal and conditionally showing `<report-viewer>` when `isStreaming() && !researchSession()?.finalReport`
- Fix the fragile string matching in `handleStepComplete()` to use structured payload data for step completion instead of substring matching on JSON.stringify output
- Add error handling to `loadSession()` in ActiveResearchComponent to surface session fetch failures to users
- Display intermediate sub-topic findings during research, not just the final report

## Capabilities

### New Capabilities

- `streaming-content-display`: Display streaming content from backend SSE events during active research phase, before the final report is completed. Shows sub-topic findings as they arrive and makes the accumulated reportContent signal visible to the user.

### Modified Capabilities

None — existing capabilities are being fixed rather than having their requirements changed. The step-list capability needs its display logic extended to handle dynamic steps created during streaming.

## Impact

- `src/app/core/services/research.service.ts` — Step list handling, content accumulation signals, SSE event handlers
- `src/app/features/active-research/active-research.component.ts` — Display conditions for report viewer, session loading error handling
- `src/app/shared/components/step-list/` — May need updates to support dynamically created steps during streaming
