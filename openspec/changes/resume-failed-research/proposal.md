# Proposal: Resume Failed / Cancelled Research Sessions

## Why (Motivation)
When a research run fails — mid-pipeline (a sub-topic exhausts its retries and the remaining ones are skipped, e.g. LLM timeout), at the end (report streaming dies), or orphaned by a server restart (stuck `PROCESSING` until the cleanup scheduler flips it to `CANCELLED`) — the user's only options today are deleting the session or starting a brand-new research run. A fresh run re-pays the full LLM cost for sub-topics that already completed successfully, and on local models each sub-topic can take minutes.

The good news: the pipeline is already durable enough to resume from. Steps are embedded in the session document and persisted incrementally:

- The `BREAKDOWN` step content is the complete plan JSON (`{topic, subTopics:[{id,title,description,searchQueries}]}`) — the original plan is recoverable without any LLM call.
- Every completed `SUBTOPIC` step stores its accumulated note (Findings + "Sources Consulted" bullets), from which captured sources can be re-parsed with the existing `collectSourceInfo` logic.

## What Changes

### Added
- **`POST /api/research/{sessionId}/resume`** — resumes a `FAILED` or `CANCELLED` session. Returns the session (now `PROCESSING`) with its SSE stream URL, same shape as `POST /api/research`. 404 if not found; 409 if the session is `COMPLETED` or already `PROCESSING`.
- **`ResearchOrchestratorService.resumeResearchAsync(sessionId)`** — async resume pipeline:
  1. Recovers the plan by parsing the `BREAKDOWN` step content JSON into `List<SubTopic>`; falls back to a fresh breakdown (default sub-topic count) only if no usable plan exists.
  2. For each planned sub-topic: if a `COMPLETED` SUBTOPIC step with non-blank content exists at that position, its note is **reused verbatim** (sources re-parsed via `collectSourceInfo`); otherwise the sub-topic is re-researched from scratch with the normal round/retry loop.
  3. Replaces/updates the FAILED (or absent) step in place — same `orderIndex`, status flipped to `COMPLETED`, content set to the fresh note.
  4. Re-runs the quality gate (+ supplementary research on failure) and generates the final streamed report exactly as a normal run does.
- **`ResearchSessionRepository.markResumableIfTerminal(sessionId, ...)`** — atomic conditional update: `FAILED`/`CANCELLED` → `PROCESSING`, clears the `"Failed: ..."` finalReport, refreshes `updatedAt`. Mirrors the existing `markCancelledIfProcessing` pattern; a second concurrent resume loses the race and is rejected.
- **Frontend "Resume research" button** — shown on `FAILED`/`CANCELLED` sessions in `history-detail` and in the live `active-research` view when a run ends in failure. Clicking it calls the resume endpoint, then navigates to the active-research route for that session and reconnects SSE (existing `connectSse(sessionId)`).

### Modified
- **`ResearchController`** — new `@PostMapping("/{sessionId}/resume")` delegating to the orchestrator.
- **`research.service.ts`** — new `resumeResearch(sessionId)` method.
- **`history-detail` / `active-research` components** — resume affordance (see above).

### Removed
- Nothing.

## Impact
- **Affected specs**: `research-resume` (new capability).
- **Storage**: no migration — resume reuses the existing embedded steps; only status/`finalReport`/step fields are rewritten on the same document.
- **API compatibility**: purely additive endpoint; no existing contract changes.
- **Cancellation**: a resumed run registers a fresh `ResearchCancellationRegistry` handle, so the existing `DELETE /api/research/{sessionId}` cancels it identically to a new run. Terminal-write guards (`ownsSessionState`) work unchanged because the session is `PROCESSING` while resuming.
- **Cleanup scheduler**: unaffected — resume refreshes `updatedAt`, and stale resumed runs are reclaimed by the existing 15-min rule.
- **Known limitation (accepted)**: a sub-topic that was *in flight* when the run died has no persisted partial note (notes persist only on sub-topic completion), so it is re-researched from scratch. Also, if the original breakdown itself failed and no plan JSON exists, resume re-plans with the default sub-topic count (the original request's `subTopicCount`/`maxIterations` are not stored).
