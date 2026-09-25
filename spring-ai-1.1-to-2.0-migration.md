# Migrating Spring AI 1.1.x → 2.0.0

> Source of truth: Spring's official [Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html), the 2.0.0-M3 release notes, and the 2.0.0 GA announcement.
> Spring AI 1.1.0 GA: Nov 2025 · Spring AI 2.0.0 GA: Jun 12, 2026 (2.0.1 also exists now).

## 0. The reality check

Spring AI 2.0 is **not a drop-in version bump** — it's a rewrite on a new baseline. The official docs call it "an application upgrade project."

Three foundation changes drive almost everything:
- **Spring Boot 4.0/4.1 + Spring Framework 7.0 are mandatory.** You cannot stay on Boot 3.x. Migrate to Boot 4 first.
- **Jackson 2 → Jackson 3** (`com.fasterxml.jackson` → `tools.jackson`).
- **Full JSpecify null-safety** across the API.

Two OpenRewrite recipes automate a large chunk (see §13). Even so, expect compile errors to guide you — treat the migration as iterative ("fix what the compiler complains about").

---

## 1. Prerequisites

- **Spring Boot 4.0 or 4.1** (Spring Framework 7). If your app is on Boot 3, do that migration first — 2.0 will not work on Boot 3.
- **Java 17+** baseline (Boot 4 baseline; Kotlin 2.x if you use Kotlin).
- **Jackson 3** on the classpath. Any code importing `com.fasterxml.jackson` directly, or customizing the Jackson `JsonMapper`, needs review (§9).
- **Adopt the 2.0.0 (or 2.0.1) Spring AI BOM** and set `spring-ai.version` — don't mix a 1.x AI jar with a 2.0 BOM.
- Keep the official Upgrade Notes open — it's the source of truth below.

---

## 2. Build & dependency changes

### Version management
```xml
<!-- BOM -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>2.0.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

### Removed modules (delete the dependency and move to the replacement)
| Removed | Use instead |
|---|---|
| `spring-ai-azure-openai` (+starter/autoconfig) | `spring-ai-openai` (drop the `Azure` prefix on classes/props) |
| `spring-ai-openai-sdk` (+starter/autoconfig) | `spring-ai-openai` (drop the `Sdk` suffix) |
| `spring-ai-oci-genai` related modules | External: `oracle/spring-cloud-oracle` → `spring-ai-oracle` |
| `spring-ai-azure-cosmos-db-store`, `spring-ai-model-chat-memory-repository-cosmos-db` | External: `azurecosmosdb.github.io/spring-ai` |
| `spring-ai-hanadb-store` | (removed; find alternative) |
| `spring-ai-spring-cloud-bindings` | (removed) |
| HuggingFace model/autoconfig | Moved out of the main repo |

### Renamed modules
- `spring-ai-advisors-vector-store` → `spring-ai-vector-store-advisor`.

### MCP group-id / package relocations (see §4 for the full table)
- `io.modelcontextprotocol.sdk:mcp-spring-webflux` / `mcp-spring-webmvc` → `org.springframework.ai:mcp-spring-webflux` / `mcp-spring-webmvc`.
- `org.springaicommunity:mcp-annotations` → provided by Spring AI's `spring-ai-mcp-annotations` (remove your direct dependency).

---

## 3. Core API: ChatClient vs ChatModel + tool calling (biggest behavioral change)

**What changed and why.** In 1.x every `ChatModel` had its own hidden tool-execution loop. In 2.0 that internal loop is **removed from every model**; tool calling is lifted into the `ChatClient` advisor chain as a composable `ToolCallingAdvisor`. This is the single most important behavioral shift.

**Impact:** `chatModel.call(prompt)` with tools no longer auto-executes tool calls — it returns the raw tool-call request.

**Migration:**

- **Preferred — use `ChatClient`** (auto-registers `ToolCallingAdvisor` when tools are present):
```java
chatClient.prompt(question)
    .tools(new MyTools())
    .call().content();
```

- **If you must use `ChatModel` directly** (custom orchestrator), drive the loop manually with `ToolCallingManager`:
```java
ToolCallingManager tcm = ToolCallingManager.builder().build();
Prompt prompt = new Prompt(List.of(new UserMessage(question)), chatOptions);
ChatClientResponse response = chatClient.prompt().messages(prompt.getMessages())
    .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false)).call().chatClientResponse();
while (response.chatResponse() != null && response.chatResponse().hasToolCalls()) {
    ToolExecutionResult result = tcm.executeToolCalls(prompt, response.chatResponse());
    prompt = new Prompt(result.conversationHistory(), chatOptions);
    response = chatClient.prompt().messages(result.conversationHistory())
        .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false)).call().chatClientResponse();
}
```

**Also removed/changed here:**
- `internalToolExecutionEnabled` is **gone** from `ToolCallingChatOptions` and all provider options (plus `spring.ai.<provider>.chat.internal-tool-execution-enabled`). Remove all `.internalToolExecutionEnabled(…​)` calls — behavior now follows the advisor, not a flag.
- `ToolExecutionEligibilityPredicate` / `DefaultToolExecutionEligibilityPredicate` removed. To customize when the loop iterates, use `ToolCallingAdvisor.builder().toolExecutionEligibilityChecker(resp -> resp != null && resp.hasToolCalls())` (or a `ToolExecutionEligibilityChecker` bean).
- `streamToolCallResponses` removed from all advisor builders + its `spring.ai.chat.client.tool-calling.stream-tool-call-responses` property. Delete those calls.
- `ToolCallAdvisor` → renamed to `ToolCallingAdvisor`.
- `ChatClient` now **always auto-registers** a `ToolCallingAdvisor`. If you added one explicitly, you now have a duplicate — remove the explicit one (or implement the `ToolAdvisor` marker / use `AdvisorParams.toolCallingAdvisorAutoRegister(false)`). New global switch: `spring.ai.chat.client.tool-calling.enabled` (default `true`).
- `tools(Consumer<ToolSpec>)` / `defaultTools(Consumer<ToolSpec>)` and `ChatClient.ToolSpec` removed. Use `.tools(myCallback)` / `.defaultTools(...)` with `ToolCallback`, `ToolCallbackProvider`, or `@Tool` POJOs, and set context via `.toolContext(Map.of(...))`.

---

## 4. MCP (Model Context Protocol)

The MCP SDK went 1.1.x → **2.0.0**, and the ecosystem was folded into Spring AI. Multiple package renames + a group-id change.

### MCP annotations moved (`org.springaicommunity.mcp.*` → `org.springframework.ai.mcp.*`)
- `org.springaicommunity.mcp.annotation.*` → `org.springframework.ai.mcp.annotation.*`
- `…​.method.*` → `org.springframework.ai.mcp.annotation.method.*`
- `…​.provider.*` → `org.springframework.ai.mcp.annotation.provider.*`

Update every import (`@McpTool`, `@McpResource`, `@McpPrompt`, `@McpSampling`, providers…). Remove your direct `org.springaicommunity:mcp-annotations` dependency — it's now transitive via `spring-ai-mcp-annotations`.

### Spring MCP transports moved (groupId + packages)
Server transports now live under `org.springframework.ai.mcp.server.web{flux,mvc}.transport`; client transports under `org.springframework.ai.mcp.client.webflux.transport`.

| Class | Old package | New package |
|---|---|---|
| WebFluxSseServerTransportProvider | io.modelcontextprotocol.server.transport | org.springframework.ai.mcp.server.webflux.transport |
| WebFluxStreamableServerTransportProvider | io.modelcontextprotocol.server.transport | org.springframework.ai.mcp.server.webflux.transport |
| WebFluxStatelessServerTransport | io.modelcontextprotocol.server.transport | org.springframework.ai.mcp.server.webflux.transport |
| WebMvcSseServerTransportProvider | io.modelcontextprotocol.server.transport | org.springframework.ai.mcp.server.webmvc.transport |
| WebMvcStreamableServerTransportProvider | io.modelcontextprotocol.server.transport | org.springframework.ai.mcp.server.webmvc.transport |
| WebMvcStatelessServerTransport | io.modelcontextprotocol.server.transport | org.springframework.ai.mcp.server.webmvc.transport |
| WebFluxSseClientTransport | io.modelcontextprotocol.client.transport | org.springframework.ai.mcp.client.webflux.transport |
| WebClientStreamableHttpTransport | io.modelcontextprotocol.client.transport | org.springframework.ai.mcp.client.webflux.transport |

If you only use the **Spring AI starters** (`spring-ai-starter-mcp-server-webflux`, `…​-webmvc`, `…​-client-webflux`), **no Java changes are needed** — just update the dependency coordinates (§2).

### `McpClientCustomizer` consolidated
`McpAsyncClientCustomizer`/`McpSyncClientCustomizer` are gone; use the single generic `McpClientCustomizer<B>`:
```java
// Before
@Bean McpSyncClientCustomizer c() { return (name, spec) -> spec.requestTimeout(Duration.ofSeconds(30)); }
// After
@Bean McpClientCustomizer<McpClient.SyncSpec> c() { return (name, spec) -> spec.requestTimeout(Duration.ofSeconds(30)); }
```
Note: `McpSyncHttpClientRequestCustomizer`/`Async` beans are no longer auto-applied by transport autoconfig — route transport customization through `McpClientCustomizer` on the transport builder.

### Other MCP items
- MCP SDK now enforces previously-optional fields as required (e.g. `CreateMessageResult.builder()` needs a `Role`; `CreateMessageRequest.builder()` needs `maxTokens`).
- `McpSchema.Tool.inputSchema()`/`outputSchema()` return `Map<String,Object>`, not `JsonSchema`.
- `McpSchema.JSONRPCMessage/Request/Result/Notification/ResourceContents/CompleteReference/Content` are **no longer sealed** — add a `default` branch to any exhaustive `switch`.
- `Builder.customizeRequest()` removed on HTTP transports → use `httpRequestCustomizer()`/`asyncHttpRequestCustomizer()`.
- MCP servers now **validate tool inputs** against JSON schema by default (disable with `validateToolInputs(false)`).
- WebMvc transport headers are normalized to **lowercase** — update any `ServerTransportSecurityValidator` lookups.

### Conversation history removed from `ToolContext`
`TOOL_CALL_HISTORY` constant and `getToolCallHistory()` removed. Tools now see only their parameters + custom context. Manage conversation history at the advisor level via `ToolCallingAdvisor` (see §3).

---

## 5. Options & configuration properties

Options are now **strictly immutable** and created only via builders (not constructors).

- `ChatOptions#copy()` and `*Options#fromOptions()` removed → use `mutate()`:
```java
OllamaChatOptions o = originalOptions.mutate().foo("...").build();
```
- `ChatModel#getDefaultOptions()` deprecated → use `getOptions()`.
- **Config-property flattening:** drop the `.options` segment from property keys.
```properties
# Before: spring.ai.openai.embedding.options.model=text-embedding-3-small
# After:  spring.ai.openai.embedding.model=text-embedding-3-small
```
In Java, use the root `*Properties` class / `toOptions()`:
```java
String model = properties.getModel();          // was properties.getOptions().getModel()
OpenAiEmbeddingOptions o = properties.toOptions();
```
- **`N()` → `n()`** in option builders (`OpenAiChatOptions.builder().N(1)` → `.n(1)`).
- **`ChatClient.options()`/`defaultOptions()` now require a Builder**, not a built `ChatOptions`:
```java
chatClient.prompt("...").options(AnthropicChatOptions.builder().maxTokens(100)).call().content();
```
(When calling `ChatModel.call(Prompt)` directly, the `Prompt` still carries a fully built concrete `ChatOptions`; no merging happens at model level.)
- Default values moved out of `*Properties`/models into option constructors. **Default temperature removed** — Spring AI no longer forces `0.7`. If you relied on it, set it explicitly (`spring.ai.openai.chat.temperature=0.7`, etc.).
- `buildRequestPrompt` removed from public API.

---

## 6. Tools: FunctionCallback → ToolCallback

The legacy `FunctionCallback` API is **removed entirely** ("tools" everywhere, "functions" nowhere), and bare `Function`/`Supplier`/`Consumer` beans resolved by `toolNames(…​)` no longer work.

**Migration — declare explicit `ToolCallback` beans or use `@Tool`:**
```java
// Option A: explicit ToolCallback bean
@Bean ToolCallback currentWeather() {
  return FunctionToolCallback.builder("currentWeather", weatherService::getWeather)
      .description("Get the weather").inputType(WeatherRequest.class).build();
}
chatClient.prompt().tools(currentWeather).call().content();

// Option B: @Tool-annotated POJO (preferred for new code)
class WeatherTools {
  @Tool(description = "Get the weather in location")
  WeatherResponse currentWeather(WeatherRequest r) { return weatherService.getWeather(r); }
}
chatClient.prompt().tools(new WeatherTools()).call().content();
```

Mapping of renamed APIs: `FunctionCallback`→`ToolCallback`; `.functionCallbacks`→`.toolCallbacks`; `FunctionCallingOptions`→`ToolCallingChatOptions`; `ChatClient#defaultFunctions`→`#defaultTools`; `.functions(…​)`→`.tools(…​)`.

---

## 7. Chat memory & advisors

**Conversation ID is now required** for built-in memory advisors (`MessageChatMemoryAdvisor`, `VectorStoreChatMemoryAdvisor`). Supply it per call via the advisor context:
```java
chatClient.prompt().user("Hello!")
    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "my-session"))
    .call().content();
```
Consequences:
- `ChatMemory.DEFAULT_CONVERSATION_ID` constant removed → use an explicit ID.
- `.conversationId(String)` builder method removed from memory-advisor builders.
- `BaseChatMemoryAdvisor.getConversationId(Map, String)` → single-arg `getConversationId(Map)` (throws if `CONVERSATION_ID` absent).
- `PromptChatMemoryAdvisor` removed → use `MessageChatMemoryAdvisor` (identical builder API).
- **JDBC chat memory** gains a `sequence_id` column (and index). Existing tables need the column backfilled (migration SQL in the upgrade notes) — or drop/recreate from the schema script. Note: retrieved messages are no longer `equals` to in-code-constructed ones (metadata changed).

**Tool-call loop & memory:** `ToolCallingAdvisor` now manages conversation history internally across loop iterations and only writes the final user/assistant exchange to the memory repository (never tool-call messages). If you need memory *inside* the loop (e.g. `InMemoryChatMemoryRepository`), set the memory advisor's order above `ToolCallingAdvisor.DEFAULT_ORDER` and call `.disableInternalConversationHistory()` on the advisor. The community **`spring-ai-session`** project is the planned replacement for `ChatMemory` (2.1).

**Advisor ordering:** `Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER` changed from `HIGHEST_PRECEDENCE + 1000` to `+200` (memory now sits *outside* the `ToolCallingAdvisor` at `+300`).

---

## 8. Provider-module specifics

- **OpenAI** (now on the official `openai-java` SDK): transition is mostly seamless; all `spring.ai.openai.*` props/builders/options intact. Caveats: tool-calling **strict mode now defaults to `false`** (opt back with `OpenAiChatOptions.builder().strict(true)`); `OpenAiAudioSpeechModel.stream(...)` now emits real audio chunks (concatenate all, don't `blockFirst()`); `Media.Builder.data(Object)` replaced with typed overloads (`data(byte[])`, `data(String)`, `data(URI)`, `data(URL)`, `data(Resource)`).
- **Azure OpenAI** + **OpenAI-SDK** modules removed → use `spring-ai-openai` (adjust class/property prefixes).
- **Anthropic** (now on `com.anthropic:anthropic-java`): `ChatClient`/`ChatModel` paths mostly unaffected, but `org.springframework.ai.anthropic.api.*` types are **gone** (use `AnthropicChatModel.builder()…`); `maxTokens` default is now `4096` (was `500`) — set it explicitly if you relied on the bound; cache/citation classes moved to root `org.springframework.ai.anthropic` (`CitationDocument`→`AnthropicCitationDocument`); old constructor `new AnthropicChatModel(new AnthropicApi(apiKey), opts)` is gone.
- **Google GenAI**: consolidated to a single GenAI SDK impl; `GoogleGenAiEmbeddingConnectionDetails` moved to `…​.embedding` package.
- **Mistral**: options now builder-based; several model constants removed/retargeted (e.g. `MAGISTRAL_MEDIUM`, `DEVSTRAL`, `OPEN_MISTRAL_NEMO` removed); moderation categories restructured (`dangerousAndCriminalContent` → `dangerous` + `criminal`, plus new `jailbreaking`).
- **Ollama**: `spring.ai.ollama.chat.think-option` → `spring.ai.ollama.chat.think`; options now builder-based.
- **Minimax** dedicated support removed → use Anthropic support with the Minimax Anthropic base URL (embeddings no longer supported).
- **DeepSeek** / **Bedrock** options now builder-based.
- **Model-internal methods** (`internalCall`/`internalStream`) are now **private** → call `call()`/`stream()` instead. If you extended model classes and overrode internals, switch to public APIs.
- **Claude 3 Opus/Sonnet/Haiku** constants removed → migrate to Claude 4.x model IDs.

---

## 9. JSON utilities (Jackson 3)

- New canonical class: `JsonHelper` (in `spring-ai-commons`); `JacksonUtils.getDefaultJsonMapper()` for the shared mapper.
- `JsonParser` deprecated → route through `JsonHelper`.
- `ModelOptionsUtils` JSON methods removed → `JsonHelper` / `JacksonUtils` / `JsonSchemaUtils`:
  - `JSON_MAPPER` → `JacksonUtils.getDefaultJsonMapper()`
  - `jsonToMap`/`jsonToObject`/`toJsonString` → `jsonHelper.*`
  - `getJsonSchema(type)` → `JsonSchemaUtils.getJsonSchema(type)`
- `McpJsonParser` deleted → `JsonHelper`.
- MCP `elicit(…​)` now takes `org.springframework.core.ParameterizedTypeReference`, not Jackson's `tools.jackson.core.type.TypeReference`.

---

## 10. Structured output (BeanOutputConverter)

- JSON Schema generation now delegates to `JsonSchemaGenerator`. Kotlin optional properties are no longer in `required`; `@JsonProperty` without `required` is treated as optional; schemas now include OpenAPI `format` hints.
- `BeanOutputConverter.postProcessSchema(JsonNode)` removed → override `generateSchema()` and post-process via `super.generateSchema()`.

---

## 11. Vector stores / filters

- `AbstractFilterExpressionConverter.doSingleValue(Object, StringBuilder)` is now **abstract** — any custom converter must implement it (use the provided `emitJsonValue`/`emitLuceneString` helpers).
- **OpenSearch** client upgraded 2.23.0 → 3.6.0 (transparent if you use the `VectorStore` interface; `OpensearchContainer`→`OpenSearchContainer`; requires `httpclient5` 5.x).
- `MongoChatMemoryRepository` now returns messages oldest→newest (remove any `Collections.reverse(...)` workaround).

---

## 12. Observability

Tool-calling spans/metrics changed: span name is now `execute_tool_<tool-name>` (was `tool_call_<tool-name>`); `gen_ai.operation.name` = `execute_tool`; new `spring.ai.tool.type` span attribute and `spring.ai.tool.call.id` attribute. `Usage` gained `getCacheReadInputTokens()`/`getCacheWriteInputTokens()`.

---

## 13. Automate with OpenRewrite

Spring ships recipes to handle the mechanical parts (imports + dependency coordinates). Example for MCP annotations:
```bash
mvn org.openrewrite.maven:rewrite-maven-plugin:6.32.0:run \
  -Drewrite.configLocation=https://raw.githubusercontent.com/spring-projects/spring-ai/refs/heads/main/src/rewrite/migrate-to-2-0-0-M3.yaml \
  -Drewrite.activeRecipes=org.springframework.ai.migration.M3MigrateMcpAnnotations \
  -Dmaven.compiler.failOnError=false
```
Related recipes: `M3MigrateMcpSpringTransports`, `M3MigrateMcpClientCustomizer`, and an **umbrella recipe** to run all at once.

Note: the recipe covers MCP imports/coordinates well, but the **behavioral** changes (tool-calling loop, options immutability, config-property flattening, memory conversation IDs, tool-callback conversion) still need manual handling. If a POM declares `io.modelcontextprotocol.sdk:mcp-spring-*` without a version (BOM-managed), patch the group-id first or Maven won't parse it.

---

## 14. Suggested order of operations

1. **Boot 4 prerequisites** — get the app on Spring Boot 4 / Spring 7 first, if not already.
2. **Bump the BOM** to 2.0.0 and fix dependency coordinates/group-ids (§2).
3. **Run the OpenRewrite MCP recipes** for the mechanical renames.
4. **Compile** and fix the first wave: package imports, Jackson 3 (`tools.jackson`), `N()`→`n()`, option builders, config-property `.options` flattening, `FunctionCallback`→`ToolCallback`.
5. **Refactor tool calling** — move `ChatModel`-internal loops to `ChatClient`/`ToolCallingAdvisor`; remove `internalToolExecutionEnabled` and `streamToolCallResponses` (§3).
6. **Fix chat memory** — per-call conversation IDs, replace removed advisors, JDBC schema migration (§7).
7. **Provider-specific cleanup** — OpenAI/Azure/Anthropic/Google/Mistral/Ollama/Minimax (§8).
8. **Final compile + runtime checks** — observability names, structured-output schema, vector-store filters.

---

## Caveats

Verified against Spring's official 2.0.0 upgrade notes, the 2.0.0-M3 release notes, and the 2.0.0 GA announcement. Not independently compiled against a real 1.1→2.0 app — treat this as a high-fidelity checklist, verify each change against your actual code, and confirm the exact current recipe filenames/IDs on the [upgrade notes page](https://docs.spring.io/spring-ai/reference/upgrade-notes.html) (the MCP recipe referenced is the M3-era one; GA may carry updated variants).
