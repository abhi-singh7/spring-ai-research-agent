# Tasks: Use Spring AI 2.0 Structured Output for LLM Calls

## Implementation
- [x] Add `LlmGateway.completeStructured(...)` overloads (`Class<T>`, `ParameterizedTypeReference<T>`); implement in `SpringAiLlmGateway` via `call().entity(...)` with a shared `prompt(...)` helper (per-request temperature options only when set)
- [x] Add `model.dto.SubTopic` record `(id, title, description, searchQueries)` and `model.dto.ResearchRoundNote` record `(findings[], sourcesConsulted[{title,url}], openQuestions[])`; delete the private mutable inner class
- [x] Rewrite `planBreakdown`: structured output via static `ParameterizedTypeReference<List<SubTopic>>`, `normalizePlan` (drop title-less, renumber ids, default descriptions), last-resort single-generic-subtopic fallback; delete `extractJsonFromMarkdown`, `parseSubTopics`, corrective-nudge retry, `truncateForLog`
- [x] Rewrite research-round execution: hybrid `researchRoundText` — attempt 1 structured (`ResearchRoundNote`), attempts 2–3 free-form fallback within the existing 3-attempt budget (500 ms × attempt backoff)
- [x] Add `renderRoundNote` rendering typed notes to the exact legacy markdown shape; `collectSourceInfo` runs on rendered text for both paths
- [x] Rewrite round prompts: "EXACT format" boilerplate → three-part description valid for JSON mode (schema appended by Spring AI) and free-form fallback
- [x] Update `ResearchFlowQualityTest` stubs to the structured seam (breakdown → PTR, rounds → Class; hybrid call-count expectations preserved); replace nudge test with retry-on-unconvertible-output test
- [x] Update `OrchestratorPersistenceIntegrationTest` stubs (structured methods at temps 0.3/0.7) and `ResearchOrchestratorServiceTest` (mock `LlmGateway`, drop deleted-method reflection tests)

## Verification
- [x] `mvn test`: 124/124 pass, including `OrchestratorPersistenceIntegrationTest` against real local MongoDB (full async run: 4 steps, unique order indexes; mid-pipeline cancel persists CANCELLED atomically)
- [x] Retry-budget invariants verified by tests: breakdown = up to 3 structured calls; one round = ≤1 structured + ≤2 free-form calls; partial-failure scenario still totals 5 LLM calls

## Deferred / Follow-ups (not part of this change)
- `useProviderStructuredOutput()` if the deployment moves to a provider with reliable native structured output (one-flag flip in `SpringAiLlmGateway`)
- Schema validation via `validateSchema()` once model reliability justifies hard failures
- Streaming structured output for the report, if/when Spring AI adds `StreamResponseSpec.entity(...)`
