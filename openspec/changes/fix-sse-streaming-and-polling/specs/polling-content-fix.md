# Spec: Polling Content Recovery & Step Description Preservation

## Requirement 1: POLLING syncs finalReport into _reportContentSignal

WHEN `pollStatus()` detects a terminal state transition (status changes from non-terminal to COMPLETED/FAILED/CANCELLED)  
AND the session response includes a non-empty `finalReport` field  
THEN `_reportContentSignal.set(session.finalReport)` is called  

## Requirement 2: POLLING preserves existing step descriptions

WHEN `pollStatus()` syncs steps from REST into `_researchStepsSignal` during terminal state transition  
AND the current step at index `i` already has a description with length > 0 (populated by SSE events)  
THEN that existing description is preserved instead of being overwritten by empty REST value  

## Requirement 3: POLLING falls back to REST content for new steps

WHEN `pollStatus()` syncs steps from REST into `_researchStepsSignal` during terminal state transition  
AND the current step at index `i` has no existing description (empty or undefined)  
THEN use `step.content || ''` from the REST response  

## Requirement 4: Active session view displays full report after completion

WHEN a completed research session is loaded via `/research/{sessionId}`  
AND polling detects COMPLETED status and syncs `finalReport` into `_reportContentSignal`  
THEN the active-research component renders the complete report content (not truncated/broken)  

## Requirement 5: Existing SSE streaming behavior unchanged

WHEN SSE events arrive during research (PROGRESS, CONTENT, REPORT_CHUNK, etc.)  
THEN they continue to populate `_researchStepsSignal` and `_reportContentSignal` as before  
AND polling only takes over when SSE fails to connect or disconnects unexpectedly  
