# Design: Improve Research Quality Pipeline

## LlmGateway seam
`LlmGateway` is the only object the orchestrator talks to for LLM calls:

```java
String complete(String systemPrompt, String userMessage, Double temperature);
Flux<String> streamComplete(String systemPrompt, String userMessage, Double temperature);
```

- **SpringAiLlmGateway** wraps the existing `ChatClient` bean (the one built in `ChatClientConfig.chatClient()` with `.defaultTools(webSearchTool, urlReaderTool)`). Per-request options are a *temperature-only* `DefaultChatOptions`; Spring AI's `ModelOptionsUtils.mergeOption` copies only non-null fields over builder defaults, so the configured model/base-url/tools stay intact (verified against Spring AI 1.1.7 jars).
- **Tests** mock this interface with plain Mockito — no fluent-API deep stubs, no Spring context.

## Temperatures (per phase)
| Phase | Temperature | Rationale |
|-------|-------------|-----------|
| Breakdown / follow-up | 0.3 | Plan shape should be stable and reproducible |
| Research rounds | 0.7 | Breadth of evidence gathering |
| Synthesis | 0.4 | Faithful, consistent report writing |

## Pipeline (processResearchAsync)
```
1. planBreakdown        — LLM returns JSON [{id,title,description,searchQueries[]}];
                           unparseable → one corrective retry ("JSON only") → fallback single sub-topic
2. per sub-topic: researchOneSubTopic(sessionId, subTopic, maxRounds):
      round 1           — sweep the planned search queries (web_search + read top pages)
      round k > 1       — chase Open Questions from previous note; must cite new sources where possible
      each response     — collectSourceInfo() parses "Title — URL" bullets OR bare URLs into
                           LinkedHashMap<normalizedUrl, displayLine> (preserves first-seen casing)
      stop when         — covered (≥3 distinct sources AND ≥400 chars findings)
                           OR a round adds no new source URLs (round ≥2, diminishing returns)
                           OR round == maxRounds
   retries              — llmCallWithRetry: 3 attempts per call, 500ms*attempt backoff
   failure              — sub-topic becomes FAILED step; session continues with the others
3. generateFinalReport  — streamed (REPORT_CHUNK events); synthesis input carries:
                           all findings blocks + failed-subtopics list + aggregated source list;
                           prompt mandates ## Conclusion, conflict analysis, Research Gaps & Confidence,
                           and a ## References section built ONLY from the aggregated URLs
   total failure        — only when EVERY sub-topic fails → session.fail() + ERROR event (no report)
```

## Coverage heuristic
`MIN_SOURCES_FOR_COVERAGE = 3`, `MIN_NOTE_LENGTH_FOR_COVERAGE = 400`. Deliberately cheap heuristics: they gate *iteration depth*, not correctness — synthesis quality still depends on the model reading what it claims. Tunable via constants; no DB impact.

## Default rounds
`app.research.default-max-iterations: 3` (application.yml) used when `ResearchRequest.maxIterations` is absent/null; code-side `@Value` default of 3 as well, so tests and non-yaml environments behave identically. Values <1 clamp to 1.

## Async submit
`processResearchAsync` is annotated `@Async("researchTaskExecutor")`. Direct calls (unit tests) run synchronously — the annotation only takes effect through the Spring proxy in production, matching existing test patterns.

## What intentionally did NOT change
- SSE event protocol and event names.
- `McpToolRouter` routing/fallbacks; local Java tools still backstop MCP servers.
- Tool registration (still bound to the ChatClient bean at builder level).
- DDL (`validate`) — no schema changes required by this work.
