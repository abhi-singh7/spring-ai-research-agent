# Tasks: Improve Research Quality Pipeline

## Implementation
- [x] Add `LlmGateway` interface (`complete`, `streamComplete`) + `SpringAiLlmGateway` wrapping the tools-configured `ChatClient`; per-request temperature via merged `DefaultChatOptions` (verified merge semantics against Spring AI 1.1.7)
- [x] Rewrite breakdown: planned `searchQueries[]` per sub-topic, one corrective retry on unparseable JSON, generic single-sub-topic fallback
- [x] Implement iterative rounds in `researchOneSubTopic`: primary sweep → open-question chasing; coverage heuristic (≥3 sources AND ≥400 chars); no-new-evidence early stop at round ≥2; `maxRounds` cap with `app.research.default-max-iterations` fallback
- [x] Add structured note format to research prompt (`## Findings` / `## Sources Consulted` / `## Open Questions`) + source-diversity/min-source rules
- [x] Implement `collectSourceInfo`: bullet + bare URL extraction, normalized dedup with first-seen casing preserved; aggregate across sub-topics
- [x] Rewrite synthesis prompt: mandatory sections incl. Conclusion, conflict analysis, Research Gaps & Confidence, References built ONLY from aggregated URLs (empty-list caveat rule)
- [x] Add `llmCallWithRetry` (3 attempts, 500ms×attempt backoff); per-sub-topic catch → FAILED step + continue; session fails only on total failure with ERROR event
- [x] Per-phase temperatures: planning/follow-up 0.3, research 0.7, synthesis 0.4
- [x] `@Async("researchTaskExecutor")` on `processResearchAsync` (POST returns immediately as documented)
- [x] `application.yml`: `app.research.default-max-iterations: 3`

## Verification
- [x] Unit tests (`ResearchFlowQualityTest`, mocked at LlmGateway seam): happy path + References provenance, breakdown JSON retry, partial failure documents gaps, total failure → session FAILED, thin round triggers second round (exactly 3 calls), bare URLs become references — all green
- [x] Full backend suite: `mvn test` = 104/104 pass

## Bugfix (post-review): LazyInitializationException on async thread
- The @Async pool thread has no ambient Hibernate session/OSIV; mutating the lazy `steps` bag (`session.addStep`) threw. Fixed by loading via `findByIdWithSteps` (LEFT JOIN FETCH) so the bag is initialized up front — all later step mutations and incremental `repo.save()` merges run without any session.
- Hardened error paths: catch block persistence wrapped in its own try/catch; streaming-completion callback failures handled on the Reactor thread with session.fail + ERROR event. Regression test: persistence failure does not escape the async method.

## Bugfix (post-review): duplicate key uq_session_order on incremental saves
- Root cause: step ids are DB-generated (UUIDGenerator), so local child objects keep id=null after a merge. Every subsequent `repo.save(session)` re-cascaded those "new" children → re-insert of already-persisted steps → duplicate key `(session_id, order_index)`. Fix: **adopt the merged instance** returned by every save in the pipeline (`session = sessionRepo.save(session)`) so persisted children carry real ids on later merges.
- Also fixed: catch-block error step now uses the next free index (was hardcoded 0 → collided with the persisted BREAKDOWN row); total-failure branch now persists FAILED status (previously lost silently).
- New full-context integration test (`OrchestratorPersistenceIntegrationTest`): real Hibernate + H2 with production constraints, real @Async orchestrator, mocked LlmGateway. Verified to fail against the pre-fix service (session stuck PROCESSING) and pass post-fix.

## Deferred / Follow-ups (not part of this change)
- Per-source quality scoring/ranking for References ordering
- Persisting the aggregated source list on ResearchStep (currently in step content + report only)
- Frontend display polish for "Research Gaps" sections (already rendered as markdown)
