# Spring AI 1.1.7 → 2.0.0 Migration Report — Research Agent Backend

Analyzed against: `research-agent-backend/` (all main + test sources), the attached
`spring-ai-1.1-to-2.0-migration.md` guide (used as a checklist, each item verified against
this codebase).

## TL;DR

Good news: this codebase uses a **very small slice** of the Spring AI API. The entire
Spring AI surface in use is:

| API | Where used | 2.0 impact |
|---|---|---|
| `ChatClient` (fluent `.prompt()/.system()/.user()/.options()/.call()/.stream()`) | `ChatClientConfig`, `SpringAiLlmGateway`, `FollowUpService` (+ tests) | Fluent chain unchanged. One options-related change (§4.2). Tool execution now via auto-registered `ToolCallingAdvisor` — behavior preserved, no code needed. |
| `DefaultChatOptions` (mutable constructor + setter) | `SpringAiLlmGateway.temperatureOptions()` only | **Must change** — options are immutable/builder-only in 2.0 (§4.2). |
| `@Tool` / `@ToolParam` annotations | `WebSearchTool`, `UrlReaderTool` | Unchanged in 2.0. No edits. |
| MCP client (auto-configured via starter, stdio connections) | `pom.xml` + `application.yml` only — **no direct MCP classes anywhere in Java code** | No Java changes; BOM bump only. Verify a couple of property names (§5). |

**Not used at all** (so the big 2.0 behavioral changes do NOT apply): ChatMemory advisors,
VectorStores, `FunctionCallback`/`toolNames`, `internalToolExecutionEnabled`,
`streamToolCallResponses`, `BeanOutputConverter`, `McpClientCustomizer`, MCP transport classes,
`ModelOptionsUtils` (only mentioned in a javadoc comment), structured output.

The real work splits into three parts:

1. **Spring Boot 3.5.4 → 4.x** (mandatory prerequisite for Spring AI 2.0) — this is actually the
   biggest chunk of file edits, driven by Jackson 2 → 3 (`com.fasterxml.jackson` → `tools.jackson`).
2. **Spring AI BOM bump + one Java class change** (`SpringAiLlmGateway`) + config-property flattening in both `application.yml` files.
3. **Runtime verification** of two behavioral risks (per-request options merging; MCP SDK 2.0 client startup).

Suggested order: Boot 4 first → compile → BOM bump → compile → fix the gateway → yml → run tests + live smoke test.

---

## 1. `pom.xml` — build & dependencies

File: `research-agent-backend/pom.xml`

### 1.1 Spring Boot parent (prerequisite)
- **Line 9**: `<version>3.5.4</version>` → latest Boot 4.x (e.g. `4.0.x`/`4.1.x`).
  Spring AI 2.0 requires Spring Boot 4 / Spring Framework 7 — it will not run on Boot 3.
- Java version stays `21` (Boot 4 baseline is 17+).

### 1.2 Spring AI BOM
- **Line 22**: `<spring-ai.version>1.1.7</spring-ai.version>` → `2.0.0` (or `2.0.1`).

### 1.3 Dependencies — no additions/removals needed
- `spring-ai-starter-model-openai` — keep as-is; still the correct artifact in 2.0.
- `spring-ai-starter-mcp-client-webflux` — keep as-is (BOM-managed). The underlying MCP SDK
  jumps 1.x → 2.0, but since you never reference MCP classes directly, no coordinate or code change.
- None of the removed/renamed modules from the guide (§2 of the guide) are used here
  (no Azure, no openai-sdk, no `spring-ai-advisors-vector-store`, no `org.springaicommunity:mcp-annotations`).
- Everything else (webflux, web, data-jpa, postgresql, validation, jsoup, lombok, test, h2) is
  Spring Boot / third-party and unaffected by the AI version itself — though some transitive
  versions move with the Boot 4 parent.

---

## 2. Jackson 2 → Jackson 3 (forced by Boot 4, not by Spring AI directly)

Boot 4 ships **Jackson 3** (`tools.jackson.*`); `com.fasterxml.jackson.*` is no longer on the
default classpath. Every import below must be rewritten. The API calls you use
(`new ObjectMapper()`, `writeValueAsString`, `readTree`, `readValue(String, TypeReference)`,
`JsonNode.path/get/has/isArray/isEmpty/asText/asBoolean`) all exist in Jackson 3, so this is a
package rename — but compile + run the tests to catch any subtle behavior shift.

| File | Line(s) | Change |
|---|---|---|
| `src/main/java/com/researchagent/config/ChatClientConfig.java` | 3 | `import com.fasterxml.jackson.databind.ObjectMapper;` → **delete** (unused import — no usage anywhere in the file) |
| `src/main/java/com/researchagent/model/entity/ResearchSession.java` | 3 | `com.fasterxml.jackson.annotation.JsonIgnore` → `tools.jackson.annotation.JsonIgnore` |
| `src/main/java/com/researchagent/model/entity/ResearchStep.java` | 3 | `com.fasterxml.jackson.annotation.JsonIgnoreProperties` → `tools.jackson.annotation.JsonIgnoreProperties` |
| `src/main/java/com/researchagent/service/ResearchOrchestratorService.java` | 3–4 | `com.fasterxml.jackson.core.type.TypeReference` → `tools.jackson.core.type.TypeReference`; `com.fasterxml.jackson.databind.ObjectMapper` → `tools.jackson.databind.ObjectMapper` |
| `src/main/java/com/researchagent/service/ResearchStreamingService.java` | 3 | `com.fasterxml.jackson.databind.ObjectMapper` → `tools.jackson.databind.ObjectMapper` (used at lines 74, 137) |
| `src/main/java/com/researchagent/tool/WebSearchTool.java` | 3–4 | `JsonNode`, `ObjectMapper` → `tools.jackson.databind.*` |
| `src/main/java/com/researchagent/tool/UrlReaderTool.java` | 3–4 | `JsonNode`, `ObjectMapper` → `tools.jackson.databind.*` |
| `src/test/java/com/researchagent/controller/ResearchControllerDeleteHistorySessionSuccessTest.java` | 3 | `ObjectMapper` → `tools.jackson.databind.ObjectMapper` |
| `src/test/java/com/researchagent/controller/ResearchControllerGetStatusTest.java` | 3 | same |
| `src/test/java/com/researchagent/controller/ResearchControllerStartResearchTest.java` | 3 | same |
| `src/test/java/com/researchagent/controller/ResearchControllerSubmitFollowUpTest.java` | 3 | same |
| `src/test/java/com/researchagent/service/ResearchFlowQualityTest.java` | 7 | same |

No code in this project uses `JsonParser`, `ModelOptionsUtils`, or `McpJsonParser` (guide §9) — nothing to do there.

---

## 3. Spring AI Java code changes

### 3.1 No-change files (verified)
- **`src/main/java/com/researchagent/service/FollowUpService.java`** — pure
  `chatClient.prompt().user(...).call().content()`. Unchanged API in 2.0.
- **`src/main/java/com/researchagent/tool/WebSearchTool.java`** and
  **`src/main/java/com/researchagent/tool/UrlReaderTool.java`** — `@Tool` / `@ToolParam`
  annotations are unchanged in 2.0. Only the Jackson imports (§2) change.
- **`src/main/java/com/researchagent/service/ResearchOrchestratorService.java`** — no direct
  Spring AI API (goes through `LlmGateway`). Only Jackson imports (§2) change.
- **`src/main/java/com/researchagent/tool/McpToolRouter.java`**,
  **`src/main/java/com/researchagent/service/McpClientErrorHandler.java`** — plain Java, no Spring AI. No changes.

### 3.2 `src/main/java/com/researchagent/service/SpringAiLlmGateway.java` — the only real API change

Current code (lines ~44–48):
```java
private DefaultChatOptions temperatureOptions(Double temperature) {
    var options = new DefaultChatOptions();
    options.setTemperature(temperature);
    return options;
}
```

In 2.0, options are **strictly immutable and builder-created** (guide §5). Replace with the
provider-specific options builder:

```java
import org.springframework.ai.openai.OpenAiChatOptions;   // new import
// remove: import org.springframework.ai.chat.prompt.DefaultChatOptions;

private OpenAiChatOptions temperatureOptions(Double temperature) {
    return OpenAiChatOptions.builder().temperature(temperature).build();
}
```

Two things to verify when you compile (the guide flags this area as "verify against your code"):

1. **`.options(...)` argument type.** Guide §5 says `ChatClient`'s `.options()`/`.defaultOptions()`
   now take a **Builder**, not a built `ChatOptions`. If the 2.0 API takes a builder, drop the
   trailing `.build()`:
   ```java
   .options(OpenAiChatOptions.builder().temperature(temperature))
   ```
   The compiler will tell you immediately which form is right.

2. **Model preservation (runtime risk — most important in this project).** In 1.x, a
   temperature-only `DefaultChatOptions` was merged over the builder defaults, so the model from
   `application.yml` (`google/gemma-4-26b-a4b-qat`) was kept. Guide §5 states that in 2.0
   "no merging happens at model level" (for direct `ChatModel.call(Prompt)`; ChatClient merge
   semantics are not spelled out). If a temperature-only per-request option causes the model to
   fall back to the SDK default, every stage of your pipeline silently uses the wrong model.
   - **Check:** after migration, log/verify which model actually receives requests during a run.
   - **Safe fallback if merging is gone:** build per-request options from the configured defaults —
     e.g. inject `OpenAiChatProperties` and do
     `properties.toOptions().mutate().temperature(t).build()` (guide §5: root `*Properties` +
     `toOptions()` + `mutate()`), or simply include `.model(...)` in the per-request builder.

Also update the class javadoc (line ~14) which references
`org.springframework.ai.model.ModelOptionsUtils` — that class's JSON helpers are gone in 2.0;
the comment is stale either way.

### 3.3 `src/main/java/com/researchagent/config/ChatClientConfig.java`

- **Line 3**: delete the unused `ObjectMapper` import (see §2).
- **No other changes needed.** Specifically:
  - `builder.defaultTools(webSearchTool, urlReaderTool)` — still valid in 2.0; `@Tool` POJOs are
    a supported tool form (guide §3/§6).
  - In 1.x, tool execution for ChatClient calls happened via the model's internal loop
    (`internalToolExecutionEnabled`, default on). In 2.0 that flag is gone and **ChatClient
    auto-registers a `ToolCallingAdvisor`** instead — so your research rounds (where the LLM must
    call `search`/`read_url`) keep working with zero code changes. You never call `ChatModel`
    directly anywhere, so you do NOT need the manual `ToolCallingManager` loop from guide §3.
  - The two `ApplicationRunner`s (`verifyMcpTools`, `toolDebugger`) make **live LLM calls at every
    startup**. They still compile in 2.0 (same fluent API), but note that with the auto
    `ToolCallingAdvisor` their `.call().chatResponse()` now returns the *final* response after any
    tool loop — same observable behavior as before. No edit required; just be aware they add
    startup latency/cost (pre-existing, not migration-related).

### 3.4 Tests that touch Spring AI

- **`src/test/java/com/researchagent/service/FollowUpServiceTest.java`** (lines 32–40):
  mocks the fluent internals `ChatClient.ChatClientRequestSpec` and
  `ChatClient.CallResponseSpec`. The chain `.prompt().user(...).call().content()` is unchanged,
  but **verify these inner interface names still exist / have the same names in 2.0** — if they
  were renamed or restructured, update the mock targets (compile errors will point at them).
- **`src/test/java/com/researchagent/service/ResearchOrchestratorServiceTest.java`** (line 31):
  only `@Mock private ChatClient chatClient;` as a constructor dependency — no API surface used.
  Should compile unchanged.
- All other tests mock `LlmGateway`, not Spring AI — unaffected.

---

## 4. `src/main/resources/application.yml`

### 4.1 Config-property flattening (guide §5: drop the `.options` segment)
Lines 7–11, before:
```yaml
      chat:
        options:
          model: ${LLM_MODEL:google/gemma-4-26b-a4b-qat}
          ##model: ${LLM_MODEL:qwopus3.6-35b-a3b-v1}
          temperature: 0.7
```
After:
```yaml
      chat:
        model: ${LLM_MODEL:google/gemma-4-26b-a4b-qat}
        ##model: ${LLM_MODEL:qwopus3.6-35b-a3b-v1}
        temperature: 0.7
```

### 4.2 MCP client block (lines 12–38) — verify, likely no change
`spring.ai.mcp.client.type`, `tool-name-prefix`, `toolcallback.enabled`, and
`stdio.connections.*` are not flagged as changed by the guide, and you use only the starter
(no transport/annotation code). Keep them; if 2.0 renamed or removed any of these keys, Spring's
unknown-property handling / startup logs will tell you. Note:
- `toolcallback.enabled: true` is the default behavior anyway — safe to delete if the key is gone.
- The MCP SDK now **validates tool inputs against JSON schema by default** (guide §4) — your MCP
  servers (`ollama_web_search`, `ddg_search`) just receive standard calls, so no action expected.

### 4.3 Unchanged (do not touch)
- `spring.ai.openai.api-key` / `base-url` — intact in 2.0.
- `spring.ai.sse.timeout: 600000` — this is **your own app property** (read by
  `ResearchStreamingService`), not a Spring AI property. Unaffected.
- Datasource/JPA/`app.*` blocks — unaffected by Spring AI. (Hibernate 7 comes with Boot 4;
  `hibernate.dialect` and `hibernate.jdbc.lob.non_contextual_creation` should still be accepted —
  watch startup logs for the LOB property in particular.)

### 4.4 Temperature default note (guide §5/§8)
2.0 no longer forces a default temperature of 0.7. You set it explicitly in the yml, and every
orchestrator LLM call passes an explicit per-stage temperature (0.3/0.7/0.4), so behavior is
preserved. The only exception is `FollowUpService.processFollowUp`, which calls ChatClient with
**no options** — it will now use whatever the property default is (your yml sets 0.7, so same as
before). No change needed; just be aware.

---

## 5. `src/test/resources/application.yml`

- **Lines 6–9**: same `.options` flattening:
  ```yaml
        chat:
          model: test-model
          temperature: 0.7
  ```
- **Line 15** `toolcallback.enabled: false`: verify the key still exists in 2.0. It's moot here
  because `stdio.connections: {}` (line 17) means no MCP clients are created in tests anyway —
  safe to delete if the key is gone.
- **Lines 30–31**: `spring.mvc.throw-exception-if-no-handler-found: true` — this property was
  **removed in Spring Boot 4** (throwing `NoHandlerFoundException` is now always the default).
  Delete these two lines; your controller tests that expect 404 behavior get it automatically.

---

## 6. Explicit "no change needed" list (so you don't hunt for them)

- `ResearchController.java`, `ResearchStreamController.java` — plain MVC/SSE, no Spring AI.
- `ResearchStreamingService.java` — Jackson import only (§2).
- `AsyncConfig.java`, `WebConfig.java`, `AbandonedSessionCleanupService.java`,
  `ResearchCancellationRegistry.java`, repositories, entities (besides §2 imports), DTOs — none.
- Nothing to do for guide items: chat memory conversation IDs (§7), vector stores/filters (§11),
  observability span names (§12), structured output (§10), OpenRewrite MCP recipes (§13 — no MCP
  annotations/transports in your code).

---

## 7. Post-migration verification checklist (compile will catch most of §2–§5; these need a live run)

1. **Model resolution with per-request options** (§3.2 risk #2): start one research session and
   confirm the OpenAI-compatible endpoint receives `model: google/gemma-4-26b-a4b-qat` for all
   stages (breakdown, research rounds, synthesis), not an SDK default.
2. **Tool-calling loop**: during a research round, confirm the LLM's `search` / `read_url` calls
   are actually executed by the auto-registered `ToolCallingAdvisor` (check logs for tool
   executions; the 1.x internal loop is gone — if tools silently stop executing, that's why).
3. **MCP stdio clients**: confirm `ollama_web_search` and `ddg_search` still start under MCP SDK
   2.0 and their tools appear with the `mcp-` prefix (startup log block in `ChatClientConfig`).
4. **SSE streaming end-to-end**: `.stream().content()` API is unchanged, but run a full session
   through the Angular UI to validate chunking + reconnection under Boot 4.
5. **Full test suite**: `mvn test` — especially `FollowUpServiceTest` (fluent-chain mocks) and the
   controller tests after the Jackson 3 rename and the removed `throw-exception-if-no-handler-found`.

---

## 8. Guide items I could not verify from here (treat as "check at compile/runtime")

The attached guide is a high-fidelity checklist; these specific claims are plausible but should
be confirmed against the actual 2.0.0 API when you compile, since they affect your code:

| Guide claim | Where it touches you | How to confirm |
|---|---|---|
| §5 "ChatClient `.options()`/`.defaultOptions()` now require a Builder" | `SpringAiLlmGateway` per-request options | Compiler error on `.options(builtOptions)` vs accepts builder |
| §5 no option merging at model level | Model preservation with temperature-only options | Runtime check #1 above |
| §4 MCP client property names unchanged | both `application.yml` files | Boot startup logs / relaxed-binding errors |
| §8 OpenAI module "mostly seamless" | `spring-ai-starter-model-openai` + all yml props | BOM resolves + app starts |
| Jackson 3 API parity for the methods you use | §2 file list | `mvn test` (WebSearchTool/UrlReaderTool parsing paths are exercised by tests) |
