# Tasks: Fix SSE Streaming & Polling Content Recovery

## Task 1 — Add SSE connection timeout fallback (Bug #1)

- [ ] Add `_sseOpened` boolean flag and `_sseTimeout` timer reference as class properties on `ResearchService`
- [ ] In `connectSse()`: set `_sseOpened = false`, start a 3-second `setTimeout` that calls `startPolling()` if SSE hasn't opened yet
- [ ] In the `'open'` event handler: set `_sseOpened = true` and clear the timeout
- [ ] Verify existing error handler still works (calls `startPolling` when CLOSED) — no changes needed

## Task 2 — Clean up timeout in disconnectSse() (Bug #1)

- [ ] In `disconnectSse()`: clear `_sseTimeout` if set, then null it out
- [ ] Reset `_sseOpened = false` for next connection attempt

## Task 3 — Sync finalReport into _reportContentSignal during polling (Bug #2A)

- [ ] In `pollStatus()` inside the terminal-state-sync block: check if `session.finalReport` exists and has length > 0, then set `_reportContentSignal.set(session.finalReport)`
- [ ] Place this BEFORE the existing step sync to ensure content is available first

## Task 4 — Preserve SSE-populated descriptions during polling step sync (Bug #2B)

- [ ] In `pollStatus()` step mapping: check if current step at index `i` in `_researchStepsSignal` already has a description
- [ ] If yes, preserve it instead of overwriting with empty REST value
- [ ] Fall back to `step.content || ''` only when no existing description exists

## Task 5 — Verify active-research component rendering

- [ ] Confirm that `shouldDisplayStreamingContent()` and `streamingContent()` computed signals work correctly with the new `_reportContentSignal` population from polling
- [ ] Ensure the template renders `researchSession().finalReport` as fallback when status is COMPLETED and content exists
- [ ] Verify the "Gathering findings..." placeholder still shows while streaming starts

## Task 6 — Manual verification

- [ ] Start a research topic → confirm SSE connects within 3 seconds (if backend is reachable) and streaming events arrive
- [ ] If backend is unreachable during startup, verify polling kicks in after 3s timeout and eventually shows the completed report from database
- [ ] Navigate to an already-completed session via `/research/{sessionId}` → verify full report displays without needing history view
