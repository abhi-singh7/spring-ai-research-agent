# Tasks — bounded-research-params

## 1. DTO ceilings

- [x] 1.1 `@Max(6)` on `ResearchRequest.maxIterations`, `@Max(10)` on `subTopicCount` (messages name the
      bound so the 400 body is actionable).

## 2. Service clamp

- [x] 2.1 `MAX_SUBTOPICS = 10`, `MAX_ROUNDS = 6`, `DEFAULT_SUBTOPIC_COUNT = 5` constants in
      `ResearchOrchestratorService`.
- [x] 2.2 `clampSubTopicCount` / `clampMaxRounds` — `null` falls back to the default / configured
      `app.research.default-max-iterations`, then clamps into range.

## 3. Persisted depth

- [x] 3.1 `ResearchSession` stores `subTopicCount` and `maxRounds` (the *effective* values).
- [x] 3.2 Fresh research uses the clamped request values.
- [x] 3.3 Resume reads `session.getSubTopicCount()` / `session.getMaxRounds()` and falls back to the
      defaults only when the document predates this change.

## 4. Tests

- [x] 4.1 `ResearchControllerStartResearchTest` — 400 for `subTopicCount = 50` and
      `maxIterations = 99` alongside the existing `@Min` violation.
- [x] 4.2 Resume tests still pass with the stored-depth lookup.

## 5. Still open

- [ ] 5.1 A per-user concurrent-session cap (counts PROCESSING sessions per owner) — needs the
      ownership field, so it follows `session-ownership`.
- [ ] 5.2 Surfacing the effective depth in the session detail DTO so the UI can show "3 of 6 rounds".
