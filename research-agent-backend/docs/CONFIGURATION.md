# Research Agent Backend — Configuration Reference

## Overview

This document covers all configuration files and their purposes in the Spring Boot backend project.

---

## Development Environment Setup

### Prerequisites

- Java 21 (OpenJDK recommended)
- Maven 3.x+ for building
- MongoDB running locally (database `research-agent` is created automatically at `mongodb://localhost:27017/research-agent`)
- Ollama or compatible LLM endpoint (for AI functionality)

### Starting the Application

```bash
cd research-agent-backend
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn spring-boot:run
# → http://localhost:8080/api/research/history
```

---

## Configuration Files

### `application.yml` — Spring Boot Configuration

The primary configuration file for all backend settings. Located at `src/main/resources/application.yml`.

#### LLM / Spring AI Configuration

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY:sk-lm-i3QQE3mA:UBjIG0O2s6HZOLVFLxvI}
      base-url: ${OLLAMA_BASE_URL:http://localhost:1234}
      chat:
        options:
          model: ${LLM_MODEL:google/gemma-4-26b-a4b-qat}
          temperature: 0.7
```

- **api-key**: API key for the OpenAI-compatible LLM endpoint. For local Ollama, any string works since Ollama doesn't require authentication.
- **base-url**: Base URL for the OpenAI-compatible REST API. Defaults to `http://localhost:1234` (Ollama's default). Change if running a different LLM backend. Note: no `/v1` suffix in the default — matches Ollama's actual endpoint structure.
- **model**: The model name for all LLM calls. Default is `google/gemma-4-26b-a4b-qat`. Commented alternative: `qwopus3.6-35b-a3b-v1`. Should match the locally available Ollama model tag.
- **temperature**: Controls randomness in text generation (0.7 = balanced creativity/determinism).

#### MCP Server Configuration

```yaml
spring.ai.mcp.client.type: SYNC
spring.ai.mcp.client.tool-name-prefix: "mcp-"
spring.ai.mcp.client.toolcallback.enabled: true
spring.ai.mcp.client.stdio.connections:
  ollama_web_search:
    command: uv
    args: ["run", "/home/abhi/ollama_web_search.py"]
    env: { OLLAMA_API_KEY: ${OLLAMA_API_KEY} }
  ddg_search:
    command: uvx
    args: ["--with", "duckduckgo-mcp-server[browser]", "duckduckgo-mcp-server", "--fetch-backend", "auto"]
```

Two stdio-based MCP servers are auto-configured by Spring AI's `spring-ai-starter-mcp-client-webflux` dependency. Each server exposes tools that the LLM can call during research. The tool-name prefix `mcp-` avoids naming conflicts with local Java tools (`WebSearchTool`, `UrlReaderTool`).

| Server | Command | Purpose |
|--------|---------|---------|
| `ollama_web_search` | `uv run /home/abhi/ollama_web_search.py` | Web search via Ollama's hosted search API (requires OLLAMA_API_KEY) |
| `ddg_search` | `uvx duckduckgo-mcp-server[browser]` | DuckDuckGo fallback search (browser extra adds Chrome TLS impersonation) |

#### Search Backend Configuration (plain HTTP — not MCP)

The preferred search/scrape backends are called directly over HTTP by the local Java tools, configured under `app.search`:

```yaml
app:
  search:
    firecrawl-base-url: ${FIRECRAWL_BASE_URL:http://localhost:3002}   # self-hosted Firecrawl API
    ollama-base-url: https://ollama.com                               # Ollama hosted web_search API
    ollama-api-key: ${OLLAMA_API_KEY:}
    tavily-api-key: ${TAVILY_API_KEY:}
```

- **firecrawl-base-url**: Base URL of the self-hosted Firecrawl API. `WebSearchTool` calls `POST {base}/v2/search` (body `{"query": ..., "limit": N}`; response `{"success": true, "data": {"web": [{url, title, description}]}}`) as the FIRST backend in every routing chain. `UrlReaderTool` calls `POST {base}/v2/scrape` (body `{"url": ..., "formats": ["markdown"]}`) as a fallback when Jsoup can't read a page.
- **Search escalation chain** (executed in ONE `WebSearchTool` call, per `McpToolRouter`): `firecrawl → ddg → ollama_web_search → tavily`. Empty/missing keys degrade to an explicit "not configured" reason instead of a network call.

#### MongoDB Configuration

```yaml
# NOTE: Spring Boot 4 moved Mongo properties from spring.data.mongodb.* to spring.mongodb.*
spring.mongodb.uri: mongodb://localhost:27017/research-agent
spring.mongodb.representation.uuid: STANDARD
```

- **uri**: Connection string for the local MongoDB. The `research-agent` database and the `research_session` collection are created automatically — no manual setup or schema migration needed (collections/indexes come from entity annotations).
- **representation.uuid**: Stores UUIDs in the standard (RFC 4122) BSON representation. It must stay in sync with the one-shot migration script `scripts/migrate_pg_to_mongo.py` — if they diverge, lookups silently fail.

**Note:** The legacy PostgreSQL schema (`db/migration/V1__init_research_tables.sql`) is retained untouched as a rollback path; no JPA/Hibernate configuration remains in the application.

#### SSE Timeout Configuration

```yaml
spring.ai.sse.timeout: 600000             # SSE emitter timeout — 10 minutes of inactivity
```

When an SSE connection has no activity for this duration, it is automatically closed by Spring's `SseEmitter`. Prevents stale connections from accumulating. Actual streaming uses `ResearchStreamingService.registerStream()` which respects this value via property injection.

#### Async Task Executor Configuration

```yaml
task:
  executor:
    core-pool-size: 5
    max-pool-size: 20
    queue-capacity: 100
```

Configures Spring's default task executor pool sizes for async research processing. Note: `AsyncConfig.researchTaskExecutor()` creates a **custom named** bean with the same values — there is duplication between these two configurations. The custom bean (`@Bean("researchTaskExecutor")`) is injected directly into `ResearchOrchestratorService.processResearchAsync()`.

#### Abandoned Session Cleanup Scheduler

```yaml
app:
  cleanup:
    stale-after: PT45M   # PROCESSING sessions older than this are considered abandoned
    interval: PT20M      # Scheduler runs at this fixed rate
```

- **stale-after**: Duration after which a stuck PROCESSING session is marked CANCELLED by the cleanup scheduler. Default `PT45M` = 45 minutes.
- **interval**: Fixed-rate interval for the cleanup task. Default `PT20M` = every 20 minutes.

---

### `pom.xml` — Maven Dependencies

#### Core Dependencies (Spring Boot 4.1.1 + Spring AI 2.0.1)

| Dependency | Purpose |
|-----------|---------|
| `spring-boot-starter-parent:4.1.1` | Spring Boot project parent (dependency management, plugin conventions) |
| `spring-ai-bom:2.0.1` | Spring AI BOM — all Spring AI artifacts use this for consistent versions |
| `spring-ai-starter-model-openai` | Auto-configures OpenAI-compatible chat model client from application.yml properties |
| `spring-ai-starter-mcp-client-webflux` | MCP client with WebFlux-based transport (recommended for production streaming) |
| `spring-boot-starter-webflux` | Reactive/WebFlux support required for SSE streaming endpoints |
| `spring-boot-starter-web` | REST web server support (Tomcat embedded, Spring MVC) |
| `spring-boot-starter-data-mongodb` | MongoDB data access via Spring Data MongoDB (replaces the former JPA starter + PostgreSQL driver) |
| `spring-boot-starter-validation` | Bean validation (@NotBlank, @Min annotations) |

#### Development Dependencies

| Dependency | Scope | Purpose |
|-----------|-------|---------|
| `jsoup:1.20.1` | Compile | HTML content extraction for `UrlReaderTool` — used by MCP fallback tool |
| `lombok` | Provided | Annotation processor for @Data, @SlfJ4 (compile-time only) |
| `spring-boot-starter-test` | Test | JUnit 5, Mockito, Spring Test support |
| `spring-boot-webmvc-test` | Test | MVC test slice (`@WebMvcTest`) — split into its own artifact in Spring Boot 4 |

**Note:** No schema migration tooling is in use. The legacy PostgreSQL DDL file (`db/migration/V1__init_research_tables.sql`) is retained untouched as a rollback path; MongoDB collections/indexes are auto-created from entity annotations.

---

## Environment Variables Reference

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `OPENAI_API_KEY` | No* | (embedded default) | API key for LLM endpoint. A default is embedded in application.yml but env var overrides it. *Required only if using a remote LLM; local Ollama accepts any value. |
| `OLLAMA_BASE_URL` | No | http://localhost:1234 | Base URL for OpenAI-compatible REST API (Ollama by default) |
| `LLM_MODEL` | No | google/gemma-4-26b-a4b-qat | Model name — should match locally available Ollama model tag |
| `OLLAMA_API_KEY` | Conditional | — | Bearer token for the ollama_web_search MCP server and the Ollama hosted web_search backend |
| `FIRECRAWL_BASE_URL` | No | http://localhost:3002 | Base URL of the self-hosted Firecrawl API (search + scrape backends) |
| `TAVILY_API_KEY` | Conditional | — | Bearer token for the Tavily search API (final fallback backend); empty → backend reports "not configured" |

---

## Key Files Summary

| File | Purpose | Category |
|------|---------|----------|
| `pom.xml` | Maven project config, dependencies, BOM management | Configuration |
| `src/main/resources/application.yml` | Spring Boot: MongoDB URI, AI/LLM, MCP servers, task executor, SSE timeout, cleanup scheduler | Configuration |
| `src/main/java/com/researchagent/config/ChatClientConfig.java` | ChatClient bean (auto-configured by Spring AI from properties) | Config class |
| `src/main/java/com/researchagent/config/AsyncConfig.java` | Named ThreadPoolTaskExecutor bean for research processing | Config class |
| `src/main/java/com/researchagent/config/WebConfig.java` | CORS configuration for Angular dev server origins | Config class |
| `src/main/resources/db/migration/V1__init_research_tables.sql` | Legacy PostgreSQL schema DDL — retained untouched as a rollback path (not used at runtime) | Migration |
| `scripts/migrate_pg_to_mongo.py` | One-shot PG→Mongo data migration (idempotent, read-only against PostgreSQL) | Migration |

---

## Spring AI 2.0 Notes

The application uses **Spring AI 2.0.1**. Key configuration patterns:

### Artifact Names
- `spring-ai-starter-model-openai` — auto-configures OpenAI-compatible chat client from properties (no manual bean definitions needed)
- `spring-ai-starter-mcp-client-webflux` — MCP client with reactive WebFlux transport for streaming support

### Streaming API
`.stream().content()` returns `Flux<String>` directly — no mapping needed. This is used in Phase 3 of the research pipeline to stream report chunks via SSE.
