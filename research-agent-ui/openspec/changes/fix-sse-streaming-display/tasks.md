## 1. Fix Step List Not Populated During Streaming (CRITICAL)

- [x] 1.1 Remove empty-steps guard in `handleProgress()` and create dynamic "Researching Sub-topics" step when `_researchStepsSignal` is empty on first PROGRESS event
- [x] 1.2 Update REPORT_START handler to remove placeholder step before adding real sub-topic steps (deduplication)

## 2. Display Streaming Content During Active Research (CRITICAL)

- [x] 2.1 Add `shouldDisplayStreamingContent` computed signal in research.service.ts
- [x] 2.2 Create `streamingContent` computed signal that returns streaming content or finalReport depending on session state
- [x] 2.3 Update active-research.component.ts template to conditionally show `<report-viewer>` with streaming content during PROCESSING status

## 3. Fix Step Completion Matching Logic (CRITICAL)

- [x] 3.1 Replace `JSON.stringify` substring matching in `handleStepComplete()` with multi-strategy: string payload → index/stepNumber payload → fallback to last IN_PROGRESS step

## 4. Add Error Handling for Session Loading

- [x] 4.1 Replace empty error handler in ActiveResearchComponent.loadSession() with user-facing error messages via researchService.error$
- [x] 4.2 Add error banner UI component (MatCard) to active-research.component.ts template with dismiss button
- [x] 4.3 Add errorMessage signal, error subscription, and dismiss method to ActiveResearchComponent

## 5. Add Streaming Content Loading Indicator

- [x] 5.1 Add spinner + "Gathering findings..." placeholder in active-research.component.ts template shown while streaming content is being received but not yet available
- [x] 5.2 Add CSS styles for spinner animation and dark mode support

## 6. Verify Progress Bar (No Code Changes — Verification Only)

- [x] 6.1 Confirm `progressPercent()` returns non-zero during sub-topic research after Task 1 is implemented (step list now populated)
- [x] 6.2 Confirm progress bar shows ~5% when "Researching Sub-topics" has IN_PROGRESS status and reaches 100% when all steps complete
