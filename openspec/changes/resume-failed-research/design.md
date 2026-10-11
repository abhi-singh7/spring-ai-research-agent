# Design: Resume Failed / Cancelled Research Sessions

## Context
The research pipeline (`ResearchOrchestratorService.processResearchAsync`) is a linear state machine over the session document: BREAKDOWN step → per-sub-topic SUBTOPIC steps (persisted incrementally) → QUALITY_EVAL step → streamed FINAL_REPORT. Every `save()` atomically replaces the whole document (session + embedded steps). Terminal writes are guarded by `ownsSessionState(sessionId)` (cancelled flag + DB status still `PROCESSING`), and cancellation is cooperative via `ResearchCancellationRegistry`.

Resume must fit this architecture without a new collection, a migration, or changes to the streaming/cancellation contract.

## Decisions

### D1 — Resume in place on the same session document (no fork)
A resume reuses the existing session id: status flips back to `PROCESSING`, the `"Failed: ..."` finalReport is cleared, and new/updated steps are written into the same embedded list. This keeps history, follow-ups, `llm_logs` attribution and the SSE endpoint (`/api/research/stream/{sessionId}`) all unchanged — the frontend just reconnects to the stream for the same id.

**Alternative rejected**: create a *new* session seeded with the old steps. It duplicates history entries, breaks follow-up continuity, and forces new DTO plumbing.

### D2 — Atomic claim before any work
Resume eligibility is enforced by a single conditional Mongo update (`markResumableIfTerminal`): filter `status ∈ {FAILED, CANCELLED}`, set `status=PROCESSING`, clear `finalReport`, refresh `updatedAt`. The async pipeline starts only if the update matched one document. This makes double-resume (double-click, two tabs) impossible without a lock, and mirrors the proven `markCancelledIfProcessing` pattern already used for cancellation.

### D3 — Plan recovery from the BREAKDOWN step
The breakdown step content is exactly `objectMapper.writeValueAsString({topic, subTopics})` produced by `normalizePlanJson`. Resume deserializes it into `List<SubTopic>` and reuses titles/descriptions/search queries verbatim. Only when no BREAKDOWN step exists or its JSON is unparseable does resume call `planBreakdown` again (default count) — this covers sessions that failed *during* the breakdown stage, where nothing else was persisted.

### D4 — Sub-topic reuse rule
For planned sub-topic at position `i` (1-based), look up the existing SUBTOPIC step with `orderIndex == i`:
- status `COMPLETED` and non-blank content → **skip research**; append its content to `findingsBlocks` and run `collectSourceInfo(content, aggregatedSources)` to rebuild the source list for References.
- status `FAILED`, absent, or blank → re-research from scratch via the existing `researchOneSubTopic(handle, sessionId, subTopic, maxRounds)`.

On completion the step is written **in place** (same `orderIndex`): a FAILED step gets its status/content replaced; a missing step is appended. This keeps the step list stable and the UI's per-sub-topic progress meaningful (completed sub-topics stay completed).

In-flight partial notes are not persisted today (a sub-topic note is saved only when all its rounds finish), so an interrupted sub-topic is always re-researched from round 1. Accepted limitation — no schema change needed.

### D5 — Post-research stages always re-run
The quality gate and report generation are never "reused": on resume they run fresh over the (possibly partly reused) findings. A session that failed at report streaming therefore resumes with zero research cost — only the eval + synthesis calls. The FINAL_REPORT step is appended at `steps.size()` exactly as in a new run; if a stale QUALITY_EVAL/FINAL_REPORT step from a prior partial attempt exists it is simply superseded by the newer one (steps are append-only, orderIndex-ordered).

### D6 — Cancellation and terminal guards unchanged
`resumeResearchAsync` registers a fresh `CancellationHandle`, checks `handle.ensureActive()` at every existing checkpoint, and writes terminals through the same `ownsSessionState` guard. `cancelResearch` works unmodified because it only requires status `PROCESSING`. The cleanup scheduler reclaims a crashed resume run after `app.cleanup.stale-after` exactly as it does today.

### D7 — API shape
`POST /api/research/{sessionId}/resume` → `200 ResearchResponse {id, topic, status: "PROCESSING"}` (same DTO as start). Errors: `404` unknown session, `409` if `COMPLETED` or already `PROCESSING`. No request body — resume parameters are recovered from the document (D3/D4), keeping the contract minimal.

### D8 — Frontend
- `history-detail`: a "Resume research" button rendered when `status ∈ {FAILED, CANCELLED}` (never for `COMPLETED`). On success it navigates to the active-research route with the session id and calls `connectSse(sessionId)`; on 409/404 it shows an error toast.
- `active-research`: when the live run reaches `FAILED` (via SSE ERROR event or polling), show the same button so a mid-run failure can be resumed without leaving the page.
- No new routes, no new services beyond one method on `ResearchService`.

## Risks / Trade-offs
| Risk | Mitigation |
|---|---|
| Stale plan JSON shape drifts from `SubTopic` record | Deserialize leniently (unknown fields ignored); any parse failure falls back to fresh breakdown (D3) |
| Completed-step note lacks source bullets (very old sessions) | `collectSourceInfo` simply captures less; References degrade gracefully, same as a zero-source run today |
| Resume of a session with ALL sub-topics failed | Behaves like a fresh run for research — correct and useful |
| Two rapid resume requests | D2 atomic claim; loser gets 409 from the controller's pre-check or silently no-ops in the async guard |
