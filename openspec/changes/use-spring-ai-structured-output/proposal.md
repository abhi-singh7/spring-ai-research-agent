# Proposal: Use Spring AI 2.0 Structured Output for LLM Calls

## Why (Motivation)
Two pipeline stages currently obtain structured data from the LLM through hand-rolled conventions that are fragile and redundant with framework support:

1. **Breakdown planning** — the prompt demands "ONLY a valid JSON array", then `extractJsonFromMarkdown` strips markdown fences, Jackson parses it via a private mutable `SubTopic` inner class, and a bespoke *corrective-nudge* retry re-prompts the model with its own bad output when parsing fails. Spring AI 2.0 ships exactly this capability: `ChatClient.CallResponseSpec.entity(Class/ParameterizedTypeReference)` generates a JSON schema from the target type (`BeanOutputConverter`), appends strict RFC8259 format instructions to the prompt, and parses the final response (fence cleanup included) into the typed object.
2. **Research rounds** — the model returns markdown sections (`## Findings` / `## Sources Consulted` / `## Open Questions`) that are then re-parsed with regexes to recover source URLs. The note structure is a contract maintained entirely by prompt text and pattern matching.

Verified against the Spring AI 2.0.1 jars: `entity(...)` exists on `CallResponseSpec` (with `Class<T>`, `ParameterizedTypeReference<T>` and `StructuredOutputConverter<T>` overloads); `EntityParamSpec.useProviderStructuredOutput()` / `.validateSchema()` are available but intentionally NOT used (see design.md); `StreamResponseSpec` has no entity method, so the streamed synthesis report stays free-form.

## What Changes

### Added
- **`LlmGateway.completeStructured(...)`** — two overloads (`Class<T>`, `ParameterizedTypeReference<T>`) on the LLM seam; production impl is a one-liner over `call().entity(...)`. Unit tests mock the interface as before.
- **`model.dto.SubTopic`** — public record `(id, title, description, searchQueries)`, promoted out of the orchestrator's private inner class; its field names are now part of the model contract (schema generated from it).
- **`model.dto.ResearchRoundNote`** — public record `(findings[], sourcesConsulted[{title,url}], openQuestions[])` with nested `SourceRef(title, url)`.
- **Hybrid round retry in `researchOneSubTopic`** — attempt 1 of each round's existing 3-attempt budget asks for structured output; attempts 2–3 fall back to the free-form note + legacy text parsing. Same total call budget as before.
- **`renderRoundNote(...)`** — renders a typed note back to the exact markdown shape used for step content, follow-up rounds and synthesis input, so downstream consumers (UI, coverage heuristic, `collectSourceInfo`) are unchanged.

### Modified
- `ResearchOrchestratorService.planBreakdown` — structured output via `ParameterizedTypeReference<List<SubTopic>>`; normalization (`normalizePlan`) replaces the parse-time mutation; last-resort single-generic-subtopic fallback kept.
- Round prompts — the "EXACT format" markdown boilerplate is replaced with a three-part description that works for both the JSON mode (schema appended by Spring AI) and the free-form fallback.
- `SpringAiLlmGateway` — shared `prompt(...)` helper; structured methods added.
- Tests: `ResearchFlowQualityTest` re-stubbed at the structured seam; `OrchestratorPersistenceIntegrationTest` stubs updated; `ResearchOrchestratorServiceTest` drops the deleted-method reflection tests and mocks `LlmGateway`.

### Removed
- `extractJsonFromMarkdown`, `parseSubTopics`, the corrective-nudge retry, `truncateForLog`, and the private mutable `SubTopic` inner class.

## Impact Assessment
| File | Change Type | Risk Level |
|------|-------------|------------|
| `service/ResearchOrchestratorService.java` | Modified (breakdown + rounds) | Medium — core pipeline; 124/124 tests green incl. real-MongoDB integration test |
| `service/LlmGateway.java`, `service/SpringAiLlmGateway.java` | Modified (new methods) | Low — thin seam, prompt-based entity conversion |
| `model/dto/SubTopic.java`, `model/dto/ResearchRoundNote.java` | Added | Low — pure data types |
| `src/test/.../*Test.java` (3 files) | Modified | Low — same behavioral scenarios, new seam stubs |

Behavioral guarantees preserved: 3-attempt retry budget per call site (500 ms × attempt backoff), per-phase temperatures (0.3 / 0.7 / 0.4), coverage heuristic (≥400 chars AND ≥3 distinct URLs), References built only from captured "Title — URL" lines (+ bare-URL scan), cancellation checkpoints, partial-failure tolerance, SSE event protocol.
