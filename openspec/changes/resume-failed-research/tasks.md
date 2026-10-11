# Tasks: Resume Failed / Cancelled Research Sessions

## Backend
- [x] `ResearchSessionRepository`: add `markResumableIfTerminal(UUID, LocalDateTime)` — conditional update matching `status ∈ {FAILED, CANCELLED}`, setting `PROCESSING`, clearing `finalReport`, refreshing `updatedAt`; returns updated count (mirror `markCancelledIfProcessing`)
- [x] `ResearchOrchestratorService.resumeResearch(UUID)`: load session → 404/409 validation → atomic claim → kick off `processResumeAsync`
- [x] `@Async processResumeAsync(UUID)`: register fresh cancellation handle; recover plan from BREAKDOWN step JSON (`List<SubTopic>` via ObjectMapper, lenient parse; fallback to `planBreakdown` with default count); per sub-topic — reuse COMPLETED step note (re-parse sources via `collectSourceInfo`) or re-research via `researchOneSubTopic`; write steps in place at same `orderIndex`; then quality gate (+ supplementary) and streamed final report as in the normal path
- [x] Refactor shared pipeline tail (quality gate + report generation) into `runPostResearchStages(...)` so new-run and resume paths don't duplicate it
- [x] `ResearchController`: `POST /{sessionId}/resume` → 200 `ResearchResponse`; 404 unknown; 409 for `COMPLETED`/`PROCESSING` (and lost claim race)
- [x] Ensure `ownsSessionState` / terminal-write guards and `cancelResearch` work unmodified for resumed runs

## Frontend
- [x] `research.service.ts`: add `resumeAndConnect(sessionId)` — POST `/resume`, reset streaming state, reconnect SSE (with polling fallback); 409/404 surface as an error so callers can stay put
- [x] `history-detail`: "Resume research" button for `FAILED`/`CANCELLED`; on success navigate to active-research route (SSE already reconnected by the service); on 409/404 show inline error, stay put
- [x] `active-research`: show the same button when a live run ends `FAILED`; local session snapshot flips to PROCESSING while the stream reconnects

## Tests & Verification
- [x] Unit: plan recovery parses stored BREAKDOWN JSON; unparseable/missing → fresh breakdown (`ResearchOrchestratorServiceTest`)
- [x] Unit: reuse rule — completed steps skipped (no LLM research call), failed/absent re-researched, step updated in place at same orderIndex (`ResearchOrchestratorServiceTest`)
- [x] Unit: atomic claim — lost claim throws; `resumeResearch` hands off to the async pipeline only on a won claim (`ResearchOrchestratorServiceTest`)
- [x] Controller: 200 for FAILED/CANCELLED, 404 unknown, 409 for COMPLETED/PROCESSING and lost claim race (`ResearchControllerResumeResearchTest`)
- [x] Integration (local MongoDB): cancel mid-run → resume → completes with all steps, plan recovered without a new breakdown call, completed sub-topic reused; fail a session → resume → completes, failure report cleared (`OrchestratorPersistenceIntegrationTest`)
- [x] `mvn test` green; frontend builds (`npx ng build`)
