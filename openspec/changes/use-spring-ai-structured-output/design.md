# Design: Spring AI 2.0 Structured Output in the Research Pipeline

## API surface used (verified against spring-ai 2.0.1 jars)

```java
chatClient.prompt()
    .system(systemPrompt)
    .user(userMessage)
    .options(...)          // optional per-request options (temperature)
    .call()
    .entity(Class<T>)                    // or entity(ParameterizedTypeReference<T>)
```

- `BeanOutputConverter` generates a JSON schema from the target type and appends strict format
  instructions to the prompt ("Your response should be in JSON format… RFC8259 compliant… Here is
  the JSON Schema instance your output must adhere to: {schema}"). The final response — after any
  tool-call rounds (`ToolCallAdvisor` runs first) — is parsed into the type, with markdown-fence
  cleanup via `ResponseTextCleaner`. Unconvertible answers throw a `RuntimeException`.
- `StreamResponseSpec` has **no** entity method in 2.0.1 → streaming structured output is unavailable;
  the streamed synthesis report therefore stays free-form (unchanged).

## Decision 1: prompt-based `entity(...)`, NOT `useProviderStructuredOutput()`

`EntityParamSpec.useProviderStructuredOutput()` switches to provider-native schema enforcement
(`OpenAiChatOptions` implements `StructuredOutputChatOptions`). Rejected because the backend is a
**local LLM served through an OpenAI-compatible endpoint (Ollama)**: native structured-output support
on local models is unreliable, and prompt-based schema-in-prompt works on any OpenAI-compatible
endpoint. If the deployment later moves to a provider with solid native support (e.g. OpenAI),
flipping one flag in `SpringAiLlmGateway` is enough — the seam keeps that option open.

## Decision 2: no `validateSchema()`

`EntityParamSpec.validateSchema()` adds a `StructuredOutputValidationAdvisor` that throws on schema-
invalid output. Rejected: the pipeline already has bounded retries and per-sub-topic degradation, and
lenient parsing + normalization (`normalizePlan`) is more forgiving of local-model drift (missing ids,
blank descriptions). Hard validation would convert "recoverable with a retry" into the same failure
mode with an extra advisor hop.

## Decision 3: breakdown — full migration to `entity(List<SubTopic>)`

- Target type: `ParameterizedTypeReference<List<SubTopic>>` (kept as a static constant) so the wire
  format stays a bare JSON array, identical to the old hand-parsed shape.
- The corrective-nudge retry is deleted: a failed conversion is just attempt 1 of the existing
  3-attempt structured retry (`llmStructuredCallWithRetry`); each attempt already re-sends the full
  schema-augmented prompt, which is a stronger correction than echoing back bad output.
- `normalizePlan` replaces parse-time mutation: drops title-less entries, renumbers missing ids by
  position, defaults blank descriptions — same semantics as before, now on immutable records.
- Last resort (all attempts fail OR plan normalizes to empty): single generic sub-topic over the whole
  topic, unchanged.

## Decision 4: research rounds — hybrid structured → free-form within the existing budget

Each round keeps its 3-attempt budget:

| Attempt | Mode | Failure behavior |
|---------|------|------------------|
| 1 | `completeStructured(..., ResearchRoundNote.class)` | fall through to attempt 2 (free-form) |
| 2–3 | free-form `complete(...)` + legacy regex parsing | final failure → sub-topic FAILED step |

Rationale: structured output is the *preferred* path (typed findings/sources/questions, no regex for
the primary structure), but a local model that garbles JSON on round 1 should not burn the whole
round budget — attempts 2–3 degrade gracefully to the battle-tested free-form format. Total LLM call
count per round is unchanged (≤3), so retry/backoff economics and test expectations hold.

The structured note is rendered back to the **identical markdown shape** (`renderRoundNote`):

```
## Findings
- …

## Sources Consulted
- Title — https://url        (blank title → URL only; blank URLs skipped)

## Open Questions
- …                          ("None"/blank filtered)
```

so step content, the UI, follow-up-round prompts, the coverage heuristic and `collectSourceInfo` all
consume one code path. `collectSourceInfo` still runs over the rendered text on BOTH paths — it also
catches stray bare URLs inside findings, preserving the "bare URLs become References entries" behavior.

## Decision 5: follow-up + synthesis stay free-form

- `submitFollowUp` needs no structure (prose answer).
- The final report is streamed token-by-token over SSE; 2.0.1 has no streaming entity API, and the
  report's structure (sections incl. References) is a presentation contract, not machine-parsed data.

## Wire-contract note

`SubTopic` and `ResearchRoundNote` are now part of the model contract: Spring AI derives the schema
from their component names (`id`, `title`, `description`, `searchQueries`; `findings`,
`sourcesConsulted[{title,url}]`, `openQuestions`). Renaming a component changes what the model is asked
to produce — treat them like API fields.
