# Production Validation — Research Agent Backend

## Why

The Research Agent backend is a Spring Boot 3.5.4 application that orchestrates AI-powered research sessions using LLM tool calling, SSE streaming, and PostgreSQL persistence. Before any deployment (local dev, staging, or production), we must verify:

- All existing tests pass
- Every critical code path has test coverage
- Security vulnerabilities are identified and mitigated
- Configuration issues (hardcoded secrets, missing env vars) are caught
- API contracts match between frontend expectations and backend reality
- The application starts cleanly without required external services that can't be mocked

## Scope

**In scope:**
- `research-agent-backend/` — all Java source code, tests, configuration, dependencies
- Backend API endpoints (REST + SSE)
- Service layer logic
- Repository/persistence layer
- Security posture
- Configuration management
- Dependency audit
- Performance patterns (N+1 queries, connection leaks, async thread pool)

**Out of scope:**
- Angular frontend code (separate validation effort)
- Infrastructure / deployment configuration (Dockerfile, k8s manifests)
- External service integration testing (LLM provider, MCP servers)

## Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Hardcoded API key in `application.yml` default | Credential leak on any code review | Flagged as High; require env-var-only config |
| No authentication/authorization | Unauthenticated access to all research data | Flagged as Critical — must be addressed before production |
| No Flyway/Liquibase migrations with `ddl-auto: validate` | Schema drift in production, deploy failures | Flagged as Medium |
| SSE emitter leaks on connection drop | Memory leak under load | Existing cleanup via `onCompletion/onError/onTimeout`; verify |
| Async thread pool unbounded queue | OOM under sustained load | Queue capacity 100 set; monitor |

## Success Criteria

- All existing tests pass with zero failures
- Every public method in every Service has at least one unit test
- Every Controller endpoint has happy-path, validation, and error-path coverage
- Security findings are categorized (Critical/High/Medium/Low)
- Production readiness score is computed (0–100)

## Acceptance Criteria

The application is production-ready only when:
- All tests pass
- No Critical or High security issues remain unfixed
- Startup completes without errors from missing required configuration
- API contracts are verified against frontend expectations
- Database schema validation passes
- Required test coverage exists for all business logic paths
