# Research Agent Backend — Configuration Reference

## Overview

This document covers all configuration files and their purposes in the Spring Boot backend project.

---

## Development Environment Setup

### Prerequisites

- Java 21 (OpenJDK recommended)
- Maven 3.x+ for building
- PostgreSQL database (`research-agent` database must be created manually)
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
          model: ${LLM_MODEL:gemma-4-26b-a4b-it-qat }
          temperature: 0.7
```

- **api-key**: API key for the OpenAI-compatible LLM endpoint. For local Ollama, any string works since Ollama doesn't require authentication.
- **base-url**: Base URL for the OpenAI-compatible REST API. Defaults to `http://localhost:1234` (Ollama's default). Change if running a different LLM backend. Note: no `/v1` suffix in the default — matches Ollama's actual endpoint structure.
- **model**: The model name for all LLM calls. Default is `gemma-4-26b-a4b-it-qat`. Commented alternative: `qwopus3.6-35b-a3b-v1`. Should match the locally available Ollama model tag.
- **temperature**: Controls randomness in text generation (0.7 = balanced creativity/determinism).

#### MCP Server Configuration

```yaml
spring.ai.mcp.client.type: SYNC
spring.ai.mcp.client.tool-name-prefix: "mcp-"
spring.ai.mcp.client.toolcallback.enabled: true
spring.ai.mcp.client.stdio.connections:
  web_search:
    command: uv
    args: ["run", "/home/abhi/ollama_web_search.py"]
    env: { OLLAMA_API_KEY: ${OLLAMA_API_KEY} }
  searxng:
    command: npx
    args: ["-y", "mcp-searxng"]
    env: { SEARXNG_URL: http://localhost:9090 }
  excalidraw:
    command: node
    args: ["/home/abhi/excalidraw-mcp/dist/index.js", "--stdio"]
  ddg_search:
    command: uvx
    args: ["duckduckgo-mcp-server"]
```

Four stdio-based MCP servers are auto-configured by Spring AI's `spring-ai-starter-mcp-client-webflux` dependency. Each server exposes tools (like `search`, `readUrl`) that the LLM can call during research. The tool-name prefix `mcp-` avoids naming conflicts with local Java tools (`WebSearchTool`, `UrlReaderTool`).

| Server | Command | Purpose |
|--------|---------|---------|
| `web_search` | `uv run /home/abhi/ollama_web_search.py` | Web search via Ollama-powered search API (requires OLLAMA_API_KEY) |
| `searxng` | `npx -y mcp-searxng` | SearXNG meta-search engine (requires SEARXNG_URL at localhost:9090) |
| `excalidraw` | `node /home/abhi/excalidraw-mcp/dist/index.js --stdio` | Diagram generation via Excalidraw MCP |
| `ddg_search` | `uvx duckduckgo-mcp-server` | DuckDuckGo fallback search |

Local Java tools (`WebSearchTool`, `UrlReaderTool`) serve as fallback when MCP servers are unavailable. The `McpToolRouter` component determines task-type routing for MCP tools.

#### Datasource Configuration

```yaml
spring.datasource.url: jdbc:postgresql://localhost:5432/research-agent
spring.datasource.username: ${DB_USERNAME:postgres}
spring.datasource.password: ${DB_PASSWORD:postgres}
spring.datasource.driver-class-name: org.postgresql.Driver
```

- **url**: PostgreSQL JDBC connection string pointing to local database `research-agent` on port 5432. The database must be created manually before first startup — Flyway does not create databases, only manages schema migrations within an existing database.
- **username/password**: Read from environment variables with default fallback to `postgres`.

**Note:** Database creation command:
```bash
createdb -U postgres research-agent
```

#### JPA Configuration

```yaml
spring.jpa.hibernate.ddl-auto: validate          # Schema validation only — no auto-modification
spring.jpa.properties.hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation: true  # Avoids PostgreSQL LOB error in auto-commit mode
```

- **ddl-auto=validate**: Strictest mode — validates that the schema matches entity definitions on startup. Will fail to start if there are mismatches but will not modify the database in any way. Use `update` during development only.
- **hibernate.dialect**: Specifies Hibernate's PostgreSQL dialect for type mapping and SQL generation.
- **hibernate.jdbc.lob.non_contextual_creation=true**: Critical setting — instructs Hibernate to stream LOB content instead of using PostgreSQL's OID-based Large Object API, preventing the "Large Objects may not be used in auto-commit mode" error with TEXT columns.

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
    stale-after: PT1H   # PROCESSING sessions older than this are considered abandoned
    interval: PT2M      # Scheduler runs at this fixed rate (default documented as PT30M in code)
```

- **stale-after**: Duration after which a stuck PROCESSING session is marked CANCELLED by the cleanup scheduler. Default `PT1H` = 1 hour.
- **interval**: Fixed-rate interval for the cleanup task. Default `PT2M` per YAML, but the code's fallback is `PT30M`. The actual runtime value depends on which takes precedence in Spring's property resolution.

---

### `pom.xml` — Maven Dependencies

#### Core Dependencies (Spring Boot 3.5.4 + Spring AI 1.1.7)

| Dependency | Purpose |
|-----------|---------|
| `spring-boot-starter-parent:3.5.4` | Spring Boot project parent (dependency management, plugin conventions) |
| `spring-ai-bom:1.1.7` | Spring AI BOM — all Spring AI artifacts use this for consistent versions |
| `spring-ai-starter-model-openai` | Auto-configures OpenAI-compatible chat model client from application.yml properties |
| `spring-ai-starter-mcp-client-webflux` | MCP client with WebFlux-based transport (recommended for production streaming) |
| `spring-boot-starter-webflux` | Reactive/WebFlux support required for SSE streaming endpoints |
| `spring-boot-starter-web` | REST web server support (Tomcat embedded, Spring MVC) |
| `spring-boot-starter-data-jpa` | JPA data access via Spring Data JPA |
| `postgresql` | PostgreSQL JDBC driver (runtime scope) |
| `spring-boot-starter-validation` | Bean validation (@NotBlank, @Min annotations) |

#### Development Dependencies

| Dependency | Scope | Purpose |
|-----------|-------|---------|
| `jsoup:1.20.1` | Compile | HTML content extraction for `UrlReaderTool` — used by MCP fallback tool |
| `lombok` | Provided | Annotation processor for @Data, @SlfJ4 (compile-time only) |
| `spring-boot-starter-test` | Test | JUnit 5, Mockito, Spring Test support |
| `h2:2.4.240` | Test | In-memory database with PostgreSQL compatibility mode for tests |

**Note:** Flyway dependency is **commented out** in pom.xml (`<!-- <dependency> ... </dependency> -->`). Schema management relies on JPA entity validation only; the migration SQL file exists but is not actively used by a Flyway runner.

---

## Environment Variables Reference

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `DB_USERNAME` | No | postgres | PostgreSQL username for database connection |
| `DB_PASSWORD` | No | postgres | PostgreSQL password for database connection |
| `OPENAI_API_KEY` | No* | (embedded default) | API key for LLM endpoint. A default is embedded in application.yml but env var overrides it. *Required only if using a remote LLM; local Ollama accepts any value. |
| `OLLAMA_BASE_URL` | No | http://localhost:1234 | Base URL for OpenAI-compatible REST API (Ollama by default) |
| `LLM_MODEL` | No | gemma-4-26b-a4b-it-qat | Model name — should match locally available Ollama model tag |
| `OLLAMA_API_KEY` | Conditional | — | Required env var for the web_search MCP server (ollama_web_search.py) |

---

## Key Files Summary

| File | Purpose | Category |
|------|---------|----------|
| `pom.xml` | Maven project config, dependencies, BOM management | Configuration |
| `src/main/resources/application.yml` | Spring Boot: datasource, JPA, AI/LLM, MCP servers, task executor, SSE timeout, cleanup scheduler | Configuration |
| `src/main/java/com/researchagent/config/ChatClientConfig.java` | ChatClient bean (auto-configured by Spring AI from properties) | Config class |
| `src/main/java/com/researchagent/config/AsyncConfig.java` | Named ThreadPoolTaskExecutor bean for research processing | Config class |
| `src/main/java/com/researchagent/config/WebConfig.java` | CORS configuration for Angular dev server origins | Config class |
| `src/main/resources/db/migration/V1__init_research_tables.sql` | Schema DDL for both tables (exists but Flyway runner is disabled) | Migration |

---

## Spring AI 1.1.x Notes

The application uses **Spring AI 1.1.7** — a stable release well past GA. Key configuration patterns:

### Artifact Names
- `spring-ai-starter-model-openai` — auto-configures OpenAI-compatible chat client from properties (no manual bean definitions needed)
- `spring-ai-starter-mcp-client-webflux` — MCP client with reactive WebFlux transport for streaming support

### Streaming API
`.stream().content()` returns `Flux<String>` directly — no mapping needed. This is used in Phase 3 of the research pipeline to stream report chunks via SSE.
