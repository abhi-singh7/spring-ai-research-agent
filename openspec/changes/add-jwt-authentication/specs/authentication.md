# Requirements: Authentication (delta)

## ADDED Requirements

### Requirement: User registration with hashed passwords
The system SHALL provide `POST /api/auth/register` accepting `{username, password}`. It SHALL validate that `username` is 3–32 characters of `[a-zA-Z0-9_]` and unique (case-insensitive), and that `password` is at least 8 characters. Passwords SHALL be stored only as BCrypt hashes — never in plaintext or reversible form. On success the system SHALL return `201` with `{token, username}` where `token` is a valid JWT for the new user (auto-login). Duplicate usernames SHALL return `409`; invalid input SHALL return `400`.

#### Scenario: New user registers
- **WHEN** a client POSTs a valid, unseen username and an 8+ character password
- **AND THEN** a `User` document is persisted with a BCrypt hash, and the response is `201` containing a JWT that passes validation for that username

#### Scenario: Duplicate username rejected
- **WHEN** a client registers a username equal to an existing one (ignoring case)
- **AND THEN** the response is `409` and no new user document is created

#### Scenario: Weak password rejected
- **WHEN** a client registers with a password shorter than 8 characters or a malformed username
- **AND THEN** the response is `400` describing the violated rule and no user document is created

### Requirement: Login issues a signed JWT
The system SHALL provide `POST /api/auth/login` accepting `{username, password}`. On valid credentials it SHALL return `200` with `{token, username}`, where `token` is an HS256-signed compact JWT carrying claims `sub` (username), `role`, `iat`, `exp` (bounded by the configured expiry) and `iss`. Invalid credentials SHALL return `401` with a JSON `{"error": ...}` body.

#### Scenario: Valid login
- **WHEN** a client POSTs correct credentials for an existing user
- **AND THEN** the returned JWT verifies against the configured secret, carries the user's `sub` and `role`, and is not yet expired

#### Scenario: Wrong password
- **WHEN** a client POSTs a username that exists with an incorrect password
- **AND THEN** the response is `401` and no token is issued

### Requirement: Current-user endpoint
The system SHALL provide `GET /api/auth/me`, requiring a valid Bearer JWT, returning `200` with `{username, role, createdAt}` for the token's subject. Missing or invalid tokens SHALL yield `401`.

#### Scenario: Authenticated me
- **WHEN** a client calls `/api/auth/me` with a valid Bearer token
- **AND THEN** the response identifies the user named in the token's `sub` claim

### Requirement: All research endpoints require a valid Bearer token
Every existing endpoint under `/api/research/**` (create, status, cancel, report, follow-up, history list/search/detail, history delete, bulk delete) SHALL require an `Authorization: Bearer <jwt>` header carrying a valid, unexpired token. Requests with a missing, malformed or expired token SHALL receive `401` with a JSON `{"error": ...}` body and no side effects. Authenticated behavior (status codes, payloads, SSE event protocol) SHALL be identical to the pre-authentication behavior.

#### Scenario: Unauthenticated read
- **WHEN** a client GETs `/api/research/history` without an Authorization header
- **AND THEN** the response is `401` JSON and no session data is returned

#### Scenario: Valid token reads and writes
- **WHEN** a client calls any research endpoint with a valid Bearer token
- **AND THEN** the request is processed exactly as before this change (same 2xx responses, same error semantics for domain failures such as 404/409)

#### Scenario: Expired token rejected
- **WHEN** a client calls a research endpoint with a syntactically valid but expired JWT
- **AND THEN** the response is `401` and no session data is returned or mutated

### Requirement: SSE stream authenticates via query-parameter token
Because the browser `EventSource` API cannot set custom headers, `GET /api/research/stream/{sessionId}` SHALL accept the JWT as a `token` query parameter. The endpoint SHALL validate the token before opening the stream: missing or invalid tokens SHALL produce a `401` JSON response with no SSE connection established. A valid token SHALL open the stream and emit events byte-identically to the pre-authentication protocol (PROGRESS, CONTENT, REPORT_CHUNK, REPORT_DONE, STEP_COMPLETE, ERROR).

#### Scenario: Stream without token
- **WHEN** a client opens `/api/research/stream/{sessionId}` without a `token` parameter
- **AND THEN** the response is `401` JSON and no SSE stream is established

#### Scenario: Stream with valid token
- **WHEN** a client opens the stream URL with `?token=<valid jwt>`
- **AND THEN** the SSE connection opens and events flow exactly as before this change

### Requirement: Auth endpoints are publicly reachable
`POST /api/auth/register` and `POST /api/auth/login` SHALL be reachable without any prior authentication. No other endpoint SHALL be reachable unauthenticated (except the stream path, which self-validates per its requirement).

#### Scenario: Anonymous login attempt
- **WHEN** an unauthenticated client POSTs to `/api/auth/login`
- **AND THEN** the request is evaluated normally (200 with token on valid credentials, 401 otherwise) rather than being rejected by the filter chain

## UNCHANGED Requirements
Research pipeline semantics (breakdown → iterative rounds → streamed synthesis), per-sub-topic failure tolerance and cancellation checkpoints, `research_session` Mongo schema (no `ownerId` yet — data isolation is Phase 3), abandoned-session cleanup scheduler, search backend routing (firecrawl → ddg → ollama_web_search → tavily), SSE event names and payload shapes, and all existing endpoint paths/payloads are unchanged by this delta. Until Phase 3 lands, any authenticated user may access any session — an accepted, documented risk for the single-user local deployment.
