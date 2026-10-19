# Bounded research parameters

## Why

`POST /api/research` accepts `maxIterations` and `subTopicCount` with only a `@Min(1)` guard, so a
caller can ask for 500 sub-topics × 500 rounds. Each round is an LLM call plus a search sweep, so
one request can become hours of sequential work on the local model and thousands of search calls —
an unbounded resource knob on a shared endpoint. Worse, the request value is never persisted: the
resume path re-reads `app.research.default-max-iterations` from config, so a resumed session silently
runs at a different depth than the one that failed.

## What changes

- `ResearchRequest` gains Jakarta ceilings: `@Max(10)` on `subTopicCount`, `@Max(6)` on
  `maxIterations`. Out-of-range requests are rejected with 400 before the pipeline starts.
- `ResearchOrchestratorService` clamps both values again (`clampSubTopicCount`, `clampMaxRounds`) so
  direct callers that bypass the DTO are bounded too, and records the effective values on the session
  document (`subTopicCount`, `maxRounds`).
- The resume path reads the stored depth instead of re-reading config, so a resumed session replays
  the plan it was actually built with.

## Impact

- Existing in-range requests are unaffected; the ceilings match the current defaults (5 sub-topics,
  3 rounds) with headroom.
- The `app.research.default-max-iterations` config key stays the default; the ceilings are a hard
  cap independent of it.
- No migration: `subTopicCount` / `maxRounds` are new optional fields; legacy sessions fall back to
  the config default on resume.
