# Proposal: Improve Research Quality Pipeline

## Why (Motivation)
The research pipeline produced shallow results and fragile runs because of five gaps verified in code review:

1. **No iterative deepening** — the `maxIterations` request parameter was accepted but never read; each sub-topic got exactly one LLM round, so open questions were never chased despite the prompt asking for them.
2. **References without provenance** — the final report's References section asked the model to list sources it "visited", with nothing capturing which URLs were actually read; results frequently contained invented or missing citations.
3. **Weak prompts** — breakdown did not plan search queries, research had no source-diversity or minimum-source rules, and synthesis had no mandatory structure (no conflict analysis, gaps, or References contract).
4. **Zero resilience** — any single LLM/tool failure anywhere in the pipeline aborted the whole session (`session.fail()`), losing all findings gathered so far; one flaky tool call wasted an entire research run.
5. **Blocking submit + uniform temperature** — `processResearchAsync` ran synchronously on the POST request thread (executor injected but unused, no `@Async`), and planning/research/synthesis all used the same 0.7 temperature despite wanting determinism for plans and reliability for synthesis.

## What Changes

### Added
- **Iterative research rounds** — each sub-topic gets up to N LLM rounds: round 1 sweeps the planned search queries; later rounds chase `Open Questions` from the previous note. Early stop when the coverage heuristic (≥3 captured sources AND ≥400 chars of findings) is met, or a round adds no new source URLs.
- **Structured research notes** — every round returns `## Findings / ## Sources Consulted ("Title — URL") / ## Open Questions`, so subsequent rounds and synthesis work on structured evidence instead of free text.
- **Captured-source References** — "Title — URL" lines are parsed out of each note (bullet or bare URL), deduplicated by normalized URL across all sub-topics, and the synthesis prompt is forbidden from citing anything outside that list; an empty list forces an explicit confidence caveat.
- **Per-sub-topic resilience + retries** — each LLM call retries 3× with backoff; a failed sub-topic becomes a FAILED step and the session continues; only total failure fails the session, and partial failures are documented in the report under "Research Gaps & Confidence".
- **Breakdown retry** — unparseable breakdown JSON gets one corrective retry before falling back to a single generic sub-topic.
- **`LlmGateway` seam** — `complete()` / `streamComplete()` interface with per-request temperature; production impl wraps the existing tools-configured `ChatClient`; unit tests mock the interface instead of stubbing the fluent ChatClient API.
- **Per-phase temperatures** — 0.3 planning/breakdown, 0.7 research rounds, 0.4 synthesis (per-request `.options()` override verified to merge over builder defaults).
- **`@Async("researchTaskExecutor")`** on `processResearchAsync` so POST /api/research returns immediately as documented.

### Modified
- `ResearchOrchestratorService` — pipeline rewritten around the items above; constructor now takes `LlmGateway` instead of `ChatClient`.
- `application.yml` — new `app.research.default-max-iterations: 3` fallback when the request omits `maxIterations`.

### Removed (if any)
None. SSE event protocol unchanged (`PROGRESS`, `CONTENT`, `REPORT_CHUNK`, `REPORT_DONE`, `STEP_COMPLETE`, `ERROR`).

## Impact Assessment
| File | Change Type | Risk Level |
|------|-------------|------------|
| `service/ResearchOrchestratorService.java` | Rewritten (pipeline) | Medium — core behavior; covered by 6 new tests + existing suite green |
| `service/LlmGateway.java`, `service/SpringAiLlmGateway.java` | Added | Low — thin seam around existing ChatClient bean |
| `resources/application.yml` | Modified | Low — one fallback property with code-side default |
| `src/test/.../ResearchFlowQualityTest.java` | Added | Low — new tests only |
