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
- [x] Full backend suite: `mvn test` = 103/103 pass

## Deferred / Follow-ups (not part of this change)
- Per-source quality scoring/ranking for References ordering
- Persisting the aggregated source list on ResearchStep (currently in step content + report only)
- Frontend display polish for "Research Gaps" sections (already rendered as markdown)
