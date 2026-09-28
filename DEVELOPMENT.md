# Development Guide

## Local Setup

### Prerequisites

- **Java 21** (OpenJDK)
- **Node.js 20+** with npm
- **MongoDB** running locally (default URI `mongodb://localhost:27017/research-agent`)
- **Ollama** running locally with a model available (e.g., `gemma-4-26b`) at `http://localhost:1234`

### Database Setup

The backend uses MongoDB — there is no schema to create or migrate. The `research-agent` database and the `research_session` collection are created automatically on first write, and indexes are auto-created from entity annotations.

If you have legacy data in the old PostgreSQL database, run the one-shot migration script (idempotent, read-only against PostgreSQL):

```bash
cd scripts
pip install -r requirements.txt
python migrate_pg_to_mongo.py --dry-run   # preview what would be migrated
python migrate_pg_to_mongo.py             # migrate + verify
```

Verify connection:
```bash
mongosh mongodb://localhost:27017/research-agent --eval "db.research_session.countDocuments()"
```

### Backend Setup

```bash
cd research-agent-backend

# Set Java home to JDK 21
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# Start the application (port 8080)
mvn spring-boot:run

# Verify running
curl http://localhost:8080/api/research/history
```

#### Backend Configuration

All configuration is in `src/main/resources/application.yml`. Key settings:

| Setting | Default | Description |
|---------|---------|-------------|
| `spring.ai.openai.base-url` | `http://localhost:1234` | Ollama endpoint (no `/v1` suffix) |
| `spring.ai.openai.chat.options.model` | `google/gemma-4-26b-a4b-qat` | LLM model name |
| `spring.mongodb.uri` | `mongodb://localhost:27017/research-agent` | MongoDB connection (database created automatically) |
| `spring.mongodb.representation.uuid` | `STANDARD` | UUIDs stored as standard (RFC 4122) BSON — must match the migration script |
| `spring.ai.sse.timeout` | `600000` ms | SSE emitter timeout (10 min) |

Environment variables that override defaults:
- `OLLAMA_BASE_URL` — Ollama endpoint URL
- `LLM_MODEL` — model name to use
- `OPENAI_API_KEY` — API key (any value works for local Ollama)

### Frontend Setup

```bash
cd research-agent-ui

# Install dependencies
npm install

# Start dev server with proxy to backend (port 4200, proxies /api → localhost:8080)
npx ng serve --proxy-config proxy.conf.json
```

The frontend runs at `http://localhost:4200` and automatically proxies `/api/*` requests to the Spring Boot backend.

---

## Development Workflow

### Running Both Services

Start both services in separate terminals:

**Terminal 1 — Backend:**
```bash
cd research-agent-backend
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn spring-boot:run
```

**Terminal 2 — Frontend:**
```bash
cd research-agent-ui
npm start    # → http://localhost:4200
```

### Testing Endpoints Manually

```bash
# Start a new research session
curl -X POST http://localhost:8080/api/research \
  -H "Content-Type: application/json" \
  -d '{"topic": "Test topic", "maxIterations": 2, "subTopicCount": 3}'

# Get session status (replace SESSION_ID)
curl http://localhost:8080/api/research/SESSION_ID

# Check history
curl http://localhost:8080/api/research/history

# Search history by topic
curl "http://localhost:8080/api/research/history/search?query=AI"

# Cancel a running session
curl -X DELETE http://localhost:8080/api/research/SESSION_ID

# Delete historical session
curl -X DELETE http://localhost:8080/api/research/history/SESSION_ID

# Bulk delete (all-or-nothing rollback)
curl -X POST http://localhost:8080/api/research/history/bulk-delete \
  -H "Content-Type: application/json" \
  -d '["uuid-1", "uuid-2"]'

# Get final report for completed session
curl http://localhost:8080/api/research/SESSION_ID/report

# Submit follow-up question
curl -X POST http://localhost:8080/api/research/SESSION_ID/followup \
  -H "Content-Type: application/json" \
  -d '{"question": "What are the key findings?"}'
```

---

## Debugging

### Backend Debugging

- **SSE events**: Check `ResearchStreamingService` logs for event sending. Events are named SSE types (PROGRESS, REPORT_CHUNK, etc.) with payload data.
- **LLM calls**: Spring AI logs at DEBUG level show model responses. Set logging to debug: in application.yml add `logging.level.org.springframework.ai=DEBUG`.
- **Database**: inspect data directly with `mongosh mongodb://localhost:27017/research-agent` (e.g., `db.research_session.find()`, `db.research_session.getIndexes()`).

### Frontend Debugging

- **SSE connection**: Open browser DevTools → Network tab → filter by "EventSource" to see SSE frames. Check the `open`, `PROGRESS`, `REPORT_CHUNK` events arriving.
- **Error handling**: The service emits errors via `error$` observable. Subscribe in ActiveResearchComponent or check browser console for `[ResearchService]` prefixed logs (reconnection attempts, stall timer).
- **Polling fallback**: If SSE disconnects, the frontend automatically polls every 5 seconds with exponential backoff reconnection (max 3 attempts before switching to polling permanently).

### Common Issues

| Issue | Solution |
|-------|----------|
| Backend can't reach MongoDB | Ensure `mongod` is running on port 27017 and `spring.mongodb.uri` in `application.yml` points at it |
| SSE connection fails immediately | Check that backend is running on port 8080 and CORS allows localhost:4200 |
| LLM returns empty responses | Verify Ollama model is loaded (`ollama list`) and `OLLAMA_BASE_URL` matches the actual endpoint |
| Frontend proxy not working | Ensure `proxy.conf.json` exists and dev server uses `--proxy-config proxy.conf.json` |

---

## Code Conventions

### Backend (Java 21 + Spring Boot)

- **No NgModules**: All components are configured via Spring beans and auto-configured classes
- **Lombok annotations** (`@Data`, `@Slf4j`) reduce boilerplate — must have Lombok plugin installed in IDE for proper code completion
- **Constructor injection only** (no `@Autowired` on fields)
- **Validation**: Use `jakarta.validation` annotations (`@NotBlank`, `@Min`) on request DTOs

### Frontend (Angular 18+)

- **Standalone components only**: All `@Component` decorators must include `standalone: true`. No NgModules.
- **Strict mode enabled**: `strictInjectionParameters`, `strictInputAccessModifiers`, `strictTemplates` — all enforced by TypeScript compiler
- **Signal-based state**: Prefer Angular Signals over BehaviorSubject patterns for state management
- **DestroyRef cleanup**: Use `inject(DestroyRef).onDestroy()` for cleanup of SSE connections, intervals, and subscriptions instead of manual `.unsubscribe()` in `ngOnDestroy`
- **Inline styles**: Component styles are defined inline via the `styles` property on `@Component`, not separate SCSS files (except global `src/styles.scss`)
- **Material M3 theming**: Use `mat.define-theme((color: ()))`. The `all-component-themes($theme)` mixin must be wrapped in a CSS selector — cannot be called at root level.

### Testing

The backend has JUnit 5 tests under `src/test/java/com/researchagent/` covering controllers, services, and tools. Integration tests run against a locally running MongoDB using a dedicated `research-agent-test` database (collections are cleaned per test). No frontend test scripts are configured (karma tests exist but no npm script invokes them).

---

## OpenSpec Workflow

Changes follow the OpenSpec workflow tracked under `openspec/changes/<name>/`. Each change includes:
- `proposal.md` — Why this change is needed
- `design.md` — How it will be implemented
- `specs/*.md` — Detailed specification files
- `tasks.md` — Implementation tasks

Use the spec-driven coding workflow (requirement analysis → clarification → spec generation → review → implementation → build-fix → verification) when making changes.

---

## Key Files Quick Reference

### Backend
| File | Purpose |
|------|---------|
| `src/main/java/com/researchagent/config/ChatClientConfig.java` | ChatClient bean (auto-configured by Spring AI) |
| `src/main/resources/application.yml` | All backend configuration: LLM, datasource, MCP servers, SSE timeout |
| `src/main/java/com/researchagent/service/ResearchOrchestratorService.java` | Core orchestration: topic → sub-topics → tool calling → report synthesis |
| `src/main/java/com/researchagent/service/ResearchStreamingService.java` | SSE connection management via ConcurrentHashMap<UUID, SseEmitter> |
| `src/main/java/com/researchagent/controller/ResearchController.java` | REST endpoints for research lifecycle + history + bulk operations |
| `src/main/java/com/researchagent/tool/McpToolRouter.java` | Routes search tasks to MCP server chains with fallbacks |
| `src/main/java/com/researchagent/service/AbandonedSessionCleanupService.java` | Background scheduler marks stuck PROCESSING sessions as CANCELLED |

### Frontend
| File | Purpose |
|------|---------|
| `src/app/core/services/research.service.ts` | Primary service: REST + SSE with reconnection/polling fallback, signal state management |
| `src/app/core/services/research-history.service.ts` | History list pagination and deletion operations |
| `src/app/core/models/research.model.ts` | All TypeScript interfaces mirroring backend structure |
| `src/app/app.routes.ts` | Lazy-loaded route definitions for standalone components |
| `proxy.conf.json` | Dev server proxy: /api → localhost:8080 |
