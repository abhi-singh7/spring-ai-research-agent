# Research Agent Backend — Configuration Reference

## Overview

This document covers all configuration files and their purposes in the Spring Boot backend project.

---

## Development Environment Setup

### Prerequisites

- Java 21 (OpenJDK recommended)
- Maven 3.x+ for building
- PostgreSQL database (for persistence)
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

### application.yml — Spring Boot Configuration

The primary configuration file for all backend settings. Located at `src/main/resources/application.yml`.

#### Datasource Configuration

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/research-agent
    username: ${DB_USERNAME:postgres}        # Environment variable with default fallback to 'postgres'
    password: ${DB_PASSWORD:postgres}         # Environment variable with default fallback to 'postgres'
```

- **url**: PostgreSQL JDBC connection string pointing to local database `research-agent` on port 5432
- **username/password**: Read from environment variables `DB_USERNAME` and `DB_PASSWORD`. Default value is `postgres` if the env vars are not set.

**Note:** The database must be created manually before first startup — Flyway does not create databases, only manages schema migrations. Run: `createdb -U postgres research-agent`

#### JPA Configuration

```yaml
  jpa:
    hibernate:
      ddl-auto: validate          # Schema validation only — no auto-creation or modification of tables
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect   # Hibernate dialect for PostgreSQL-specific features
```

- **ddl-auto=validate**: Strictest mode — validates that the schema matches entity definitions on startup. Will fail to start if there are mismatches but will not modify the database in any way. Use `update` during development only.
- **hibernate.dialect**: Specifies Hibernate's PostgreSQL dialect for type mapping and SQL generation

#### OpenAI / Spring AI Configuration

```yaml
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}              # LLM API key — required, read from environment variable
      base-url: ${OLLAMA_BASE_URL:http://localhost:1234/v1}   # Base URL for OpenAI-compatible REST API (Ollama by default)
      chat:
        options:
          model: ${LLM_MODEL:llama3.1}         # Model name — defaults to llama3.1 if env var not set
          temperature: 0.7                     # Temperature for text generation (higher = more creative/random output)
```

- **api-key**: The API key for the OpenAI-compatible LLM endpoint. Required — no default value. For local Ollama instances, this can be any arbitrary string since Ollama doesn't require authentication.
- **base-url**: Base URL for the OpenAI-compatible REST API. Defaults to `http://localhost:1234/v1` (Ollama's default endpoint). Change if running a different LLM backend.
- **model**: The model name to use for all LLM calls. Defaults to `llama3.1`. For Ollama, this should match the locally available model tag.
- **temperature**: Controls randomness in text generation. Higher values (0.7+) produce more creative/varied outputs; lower values (0.2-) produce more deterministic/focused outputs.

#### Task Executor Configuration

```yaml
  task:
    execution:
      pool:
        core-pool-size: 5         # Minimum number of threads in the pool
        max-pool-size: 20         # Maximum number of threads allowed
        queue-capacity: 100       # Max queued tasks before rejecting new ones
```

- **corePoolSize**: Minimum idle threads maintained even when inactive
- **maxPoolSize**: Maximum threads created during peak load (up to 4x core size)
- **queueCapacity**: Tasks beyond the pool's capacity are queued here; once queue is full, new tasks are rejected (throws `RejectedExecutionException`)

**Note:** The async executor bean (`AsyncConfig.researchTaskExecutor()`) mirrors these same values — there is duplication. The application.yml configures Spring's default task executor while AsyncConfig creates a custom named executor for the research service specifically.

#### SSE Timeout Configuration

```yaml
  ai:
    sse:
      timeout: 600000             # SSE emitter timeout in milliseconds (10 minutes)
```

- When an SSE connection has no activity for this duration, it is automatically closed by Spring's `SseEmitter`
- This prevents stale connections from accumulating and consuming resources
- The actual streaming connections are established through `ResearchStreamingService.registerStream()`, which respects this timeout value via the application property injection

### pom.xml — Maven Dependencies

```xml
<properties>
  <spring.ai.version>1.0.0</spring.ai.version>   <!-- Spring AI GA release — BOM-managed dependency versions -->
</properties>
```

**Critical version note:** The `spring-ai-bom` property (version 1.0.0) is used in the `<dependencyManagement>` section to manage all Spring AI dependency versions automatically. This means you do **not** specify a version for individual Spring AI artifacts — they inherit from the BOM.

#### Core Dependencies

| Dependency | Scope | Purpose |
|-----------|-------|---------|
| `spring-boot-starter-parent:3.2.5` | parent | Spring Boot project parent (dependency management, plugin conventions) |
| `spring-ai-bom:1.0.0` | import, pom (BOM) | Spring AI dependency version management — all Spring AI artifacts use this BOM for consistent versions |
| `spring-boot-starter-web` | compile | REST web server support (Tomcat embedded, Spring MVC) |
| `spring-boot-starter-webflux` | compile | Reactive/WebFlux support required for SSE streaming endpoints |
| `spring-boot-starter-data-jpa` | compile | JPA data access via Spring Data JPA |
| `postgresql` | runtime | PostgreSQL JDBC driver (runtime dependency — not needed at compile time) |
| `flyway-core` | compile | Database migration management tool |
| `spring-ai-starter-model-openai` | compile | **Spring AI 1.0 GA** auto-configures OpenAI-compatible chat model client from application.yml properties. Replaces the old `spring-ai-openai-spring-boot-starter` artifact name (changed in Spring AI 1.0 GA). No manual bean definition needed — detects API key and base URL from configuration automatically. |
| `spring-boot-starter-validation` | compile | Bean validation support for `@NotBlank`, `@Min`, etc. annotations |

#### Development Dependencies

| Dependency | Scope | Purpose |
|-----------|-------|---------|
| `jsoup:1.17.2` | Compile | HTML content extraction/HTML parsing — **declared but not directly used in any scanned file**. May be intended for web scraping via the WebSearchTool's readUrl functionality, but no JSoup import is found in the codebase |
| `lombok:optional` | Optional | Annotation processor for reducing boilerplate (@Data, @Slf4j). Must be installed as an IDE plugin (not a runtime dependency) to work properly. The `optional=true` means it won't be included in the final artifact's classpath during packaging — only used at compile time for generating getters/setters/equals/hashCode |
| `spring-boot-starter-test` | test | Spring Boot testing support (JUnit 5, Mockito, Spring Test) |

---

### database.yml — Database Migration Configuration

Flyway configuration in application.yml:

```yaml
  flyway:
    enabled: true         # Enable Flyway for automatic schema migrations on startup
    locations: classpath:db/migration   # Look for migration files in src/main/resources/db/migration/
```

- **enabled=true**: Automatically runs pending migrations on application startup. Set to `false` during development if you need manual control over schema changes.
- **locations**: Where Flyway looks for `.sql` migration files — in this case, the `src/main/resources/db/migration/` directory.

#### Migration Files

| File | Description |
|------|-------------|
| `V1__init_research_tables.sql` | Creates both `research_session` and `research_step` tables with all constraints, indexes, and foreign keys |

**Naming convention:** `V{version}__description.sql` — Flyway version numbers are applied in order. Version 1 is the first migration; subsequent versions increment (V2, V3, etc.).

---

## Database Schema Migration (Flyway)

### First Run — Create Database Manually

The database must be created **before** running the application for the first time. Flyway only manages schema changes within an existing database — it does not create databases:

```bash
createdb -U postgres research-agent
# or with password prompt:
PGPASSWORD=postgres psql -c "CREATE DATABASE research-agent;"
```

### Schema Version Tracking

Flyway maintains a `flyway_schema_history` table in the target database that tracks which migrations have been applied. This ensures idempotent upgrades — if you run the application twice, Flyway won't re-apply V1 because it's already recorded as applied.

---

## Environment Variables Reference

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `DB_USERNAME` | No | postgres | PostgreSQL username for database connection |
| `DB_PASSWORD` | No | postgres | PostgreSQL password for database connection |
| `OPENAI_API_KEY` | **Yes** | (none) | API key for the OpenAI-compatible LLM endpoint. Required — no default value. For local Ollama, can be any arbitrary string since Ollama doesn't require authentication. |
| `OLLAMA_BASE_URL` | No | http://localhost:1234/v1 | Base URL for the OpenAI-compatible REST API (Ollama by default) |
| `LLM_MODEL` | No | llama3.1 | Model name to use for LLM calls — should match the locally available model in Ollama |

---

## Key Files Summary

| File | Purpose | Category |
|------|---------|----------|
| `pom.xml` | Maven project configuration, dependencies, BOM management | Configuration |
| `src/main/resources/application.yml` | Spring Boot config: datasource, JPA, AI/LLM, task executor, SSE timeout | Configuration |
| `src/main/resources/db/migration/V1__init_research_tables.sql` | Database schema creation for research_session and research_step tables | Migration |

---

## Async Execution Architecture

### ThreadPoolTaskExecutor Configuration (AsyncConfig)

```java
@Bean("researchTaskExecutor")
public Executor researchTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(5);
    executor.setMaxPoolSize(20);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("research-");
    executor.initialize();
    return executor;
}
```

**Named bean**: The `@Bean("researchTaskExecutor")` annotation creates a named bean that can be injected via `@Qualifier("researchTaskExecutor")` to ensure the correct executor is used. This prevents accidentally injecting Spring's default async executor instead of this custom one.

### Usage in ResearchOrchestratorService

```java
// In processResearchAsync(), the async method runs on the researchTaskExecutor:
executor.execute(() -> {
    // ... orchestration logic (Phase 1, Phase 2, Phase 3) ...
});
```

**Note:** The `@EnableAsync` annotation is present in AsyncConfig but no `@Async` methods are actually implemented. The async execution is done manually via the injected executor bean rather than using Spring's declarative `@Async` mechanism. This approach gives more control over thread pool usage and error handling within each phase of orchestration.

---

## CORS Configuration (WebConfig)

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins("http://localhost:4200", "http://127.0.0.1:4200")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowCredentials(true)
            .allowedHeaders("*");
    }
}
```

- **Patterns**: `/api/**` — applies to all API endpoints
- **Origins**: Only the Angular dev server origins are allowed (localhost:4200 and 127.0.0.1:4200)
- **Methods**: All standard HTTP methods including OPTIONS for preflight requests
- **Credentials**: `true` — allows cookies, auth headers to be sent cross-origin

**Note:** This is development-only CORS configuration. In production, the frontend and backend would likely share a domain (via reverse proxy), eliminating the need for CORS entirely.

---

## Spring AI 1.0 GA Migration Notes

The application uses **Spring AI 1.0.0 GA** — this is not a milestone version. Key changes from milestone versions:

### Artifact Name Change
- **Before (M3/M6)**: `spring-ai-openai-spring-boot-starter`
- **After (GA)**: `spring-ai-starter-model-openai`

The artifact naming convention changed in GA — the old naming is no longer available on Maven Central. The new name reflects that Spring AI now supports multiple model providers, not just OpenAI.

### Artifact Location Change
- **Before**: Available only in Spring Milestone Repository (spring-milestones)
- **After**: Available on standard Maven Central (`https://repo.maven.apache.org/maven2/`) — no need for the spring-milestones repository declaration

### Configuration Simplification
- **Before**: Manual configuration of `OpenAiApiProperties` bean, explicit `ChatClient.Builder` setup with tool callbacks
- **After**: Spring AI auto-configures everything from application.yml properties. The `ChatClientConfig` is minimal — just a basic `ChatClient` bean without manual tool registration or property setting.

### Streaming API Change
- **Before**: `.stream().map()` pattern required to extract content from response spec
- **After**: `.stream().content()` returns `Flux<String>` directly — no mapping needed
