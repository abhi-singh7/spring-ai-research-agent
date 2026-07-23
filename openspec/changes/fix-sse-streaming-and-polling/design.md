# Design: Fix SSE Streaming & Polling Content Recovery

## Overview

Two frontend-only changes in `research.service.ts` address both bugs without backend modifications.

---

## Bug #1 — SSE Connection Fallback

### Problem
`connectSse()` is called from `startResearch()` wrapped in try/catch, but `EventSource` errors are async (delivered via the `'error'` event), so the catch block never fires. If the connection silently fails, no fallback to polling occurs and no events ever arrive.

### Solution
Add a connection timeout mechanism inside `connectSse()`:

1. **Open tracking**: Set a `_sseOpened = false` flag when starting the connection. The `'open'` event handler sets it to `true`.
2. **Timeout guard**: Start a 3-second timer after creating the EventSource. If the open event hasn't fired within that window, trigger polling as fallback while keeping SSE alive (it may still connect later).
3. **Cleanup on error**: The existing `'error'` handler already calls `startPolling()` when readyState is CLOSED — this remains unchanged but now has a proper backup path via the timeout.

```typescript
// In connectSse():
private _sseOpened = false;
private _sseTimeout: ReturnType<typeof setTimeout> | null = null;

connectSse(sessionId: string, streamUrl?: string): void {
    this.disconnectSse();
    
    const url = streamUrl || `${this.baseUrl}/stream/${sessionId}`;
    this.sseSource = new EventSource(url);
    this.isStreaming.set(true);
    this._sseOpened = false;
    
    // Timeout: if SSE doesn't open within 3s, fall back to polling
    this._sseTimeout = setTimeout(() => {
        if (!this._sseOpened) {
            console.warn('[ResearchService] SSE did not connect in time, falling back to polling');
            this.isStreaming.set(false);
            this.startPolling(sessionId);
        }
    }, 3000);
    
    // ... existing event listeners unchanged
    
    this.sseSource.addEventListener('open', () => {
        console.log('[ResearchService] SSE connected for session:', sessionId);
        this._sseOpened = true;
        if (this._sseTimeout) {
            clearTimeout(this._sseTimeout);
            this._sseTimeout = null;
        }
    });
    
    // ... error handler already calls startPolling when CLOSED
}

// In disconnectSse():
disconnectSse(): void {
    if (this.sseSource) {
        this.sseSource.close();
        this.sseSource = null;
    }
    if (this._sseTimeout) {
        clearTimeout(this._sseTimeout);
        this._sseTimeout = null;
    }
    this.isStreaming.set(false);
}
```

### Why 3 seconds?
- Backend starts processing research asynchronously immediately after session creation
- First SSE events (PROGRESS for breakdown) typically arrive within 1-2 seconds of backend startup
- 3 seconds gives enough time for the connection to establish while providing a reasonable fallback window
- If SSE connects later, it takes over normally via the existing event listeners

---

## Bug #2 — Polling Content Recovery & Step Description Preservation

### Problem A: Empty `_reportContentSignal` after polling detects COMPLETED
When POLLING syncs steps from REST at terminal state transitions, `_reportContentSignal` is left untouched. If SSE never delivered chunks (Bug #1 scenario), the content signal stays empty even though `finalReport` exists in the database.

### Fix A: Sync `finalReport` into `_reportContentSignal` during polling
In `pollStatus()`, when transitioning to a terminal state, also update `_reportContentSignal`:

```typescript
private pollStatus(sessionId: string): void {
    this.getStatus(sessionId).subscribe({
        next: (session) => {
            const wasTerminal = ['COMPLETED', 'FAILED', 'CANCELLED'].includes(this.researchSession()?.status ?? '');
            
            if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(session.status) && !wasTerminal) {
                // Sync content from REST finalReport when available
                if (session.finalReport && session.finalReport.length > 0) {
                    this._reportContentSignal.set(session.finalReport);
                }
                
                const newSteps = /* existing mapping with preserved descriptions */;
                if (newSteps.length > 0) {
                    this._researchStepsSignal.set(newSteps);
                }
            }
            
            this.researchSession.set(session);
            if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(session.status)) {
                this.stopPolling();
                this.isStreaming.set(false);
            }
        },
        error: () => {}
    });
}
```

### Problem B: POLLING overwrites SSE-populated step descriptions
`ResearchResponse.StepDTO` doesn't include the `content` field that REST's detail endpoint includes. When polling syncs steps, it replaces SSE-populated descriptions with empty strings from REST.

### Fix B: Preserve existing descriptions during step sync
```typescript
if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(session.status) && !wasTerminal) {
    const newSteps = (session as any).steps?.map((step: any, i: number) => {
        const currentStep = this._researchStepsSignal()[i];
        return {
            stepNumber: step.orderIndex + 1,
            name: this.getStepName(step.type),
            status: step.status as ResearchModels.ResearchStep['status'],
            description: (currentStep?.description && currentStep.description.length > 0) 
                ? currentStep.description   // Preserve SSE-populated description
                : (step.content || '')       // Fall back to REST content if available
        };
    }) ?? [];
    
    if (newSteps.length > 0) {
        this._researchStepsSignal.set(newSteps);
    }
}
```

---

## File Changes

| File | Changes |
|------|---------|
| `research-agent-ui/src/app/core/services/research.service.ts` | Add `_sseOpened` flag, `_sseTimeout`, timeout logic in `connectSse()`, cleanup in `disconnectSse()`, content sync + description preservation in `pollStatus()` |

## No Backend Changes Required
- The backend's `/api/research/{sessionId}` already returns `finalReport` via `response.setFinalReport(session.getFinalReport())`
- Steps are included as `StepDTO[]` with type, status, and orderIndex — sufficient for polling sync
- All fixes are frontend-only

## Testing Approach
- Unit tests for the timeout mechanism (mock EventSource to test open vs no-open scenarios)
- Unit tests for polling content recovery (verify `_reportContentSignal` is set from `finalReport`)
- Unit tests for step description preservation (verify descriptions aren't overwritten by REST empty values)
