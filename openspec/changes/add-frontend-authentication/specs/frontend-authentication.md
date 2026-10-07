# Requirements: Frontend Authentication (delta)

## ADDED Requirements

### Requirement: Login screen issues and stores a session
The system SHALL provide an unauthenticated route `/login` with a username/password form. On valid credentials (`POST /api/auth/login` → 200) the frontend SHALL store the returned JWT and navigate to `/research/new`. On invalid credentials (401 `{"error": ...}`) the error text SHALL be displayed inline on the form and no navigation SHALL occur.

#### Scenario: Successful login
- **WHEN** a signed-out user submits correct credentials on `/login`
- **AND THEN** the JWT is persisted, the auth state becomes authenticated with the user's username, and the app navigates to `/research/new` where protected features work without further action

#### Scenario: Wrong password stays on the form
- **WHEN** a user submits an existing username with an incorrect password
- **AND THEN** the backend's error message is shown inline, no token is stored, and the user remains on `/login`

### Requirement: First-time registration from the login screen
The login screen SHALL offer a registration mode calling `POST /api/auth/register`. On success (201) the frontend SHALL store the returned token (auto-login) and navigate to `/research/new`. Duplicate-username (409) and validation (400) errors SHALL be displayed inline without navigation.

#### Scenario: New user registers
- **WHEN** a user switches to registration mode and submits a valid, unseen username and password
- **AND THEN** the 201 response's token is stored and the user lands on `/research/new` already signed in

#### Scenario: Duplicate username rejected inline
- **WHEN** a user registers a username that already exists (any case)
- **AND THEN** the 409 error text is shown inline and no session is created

### Requirement: Session persistence and restore on load
The frontend SHALL persist the JWT in `localStorage` under a fixed key owned by the auth service. On application start, before any route renders, the auth service SHALL validate a stored token via `GET /api/auth/me`: a valid token yields the authenticated state (username available for display); a missing, malformed or rejected token yields the anonymous state and the user is sent to `/login`.

#### Scenario: Refresh keeps the session
- **WHEN** an authenticated user reloads the page while their token is unexpired
- **AND THEN** they are not asked to log in again and their previous route renders normally

#### Scenario: Expired stored token lands on login
- **WHEN** a user loads the app with a stored token that `/api/auth/me` rejects
- **AND THEN** the stale token is discarded, the auth state is anonymous, and the user sees the login screen (not a page full of 401 errors)

### Requirement: Bearer header on all API requests
While authenticated, every `HttpClient` request to an `/api/...` endpoint SHALL carry `Authorization: Bearer <jwt>`. Auth endpoints (`/api/auth/login`, `/api/auth/register`) SHALL remain callable without a stored token.

#### Scenario: Authenticated research call
- **WHEN** a signed-in user starts research or loads history
- **AND THEN** the request includes the Bearer header and is processed exactly as Phase 1 specifies (2xx on success, domain error codes unchanged)

### Requirement: SSE stream URL carries the token
The EventSource for `GET /api/research/stream/{sessionId}` SHALL be constructed as that path with `?token=<jwt>` appended by the research service at connect time. Every reconnection SHALL use the same scheme (reading the current token, so a fresh login mid-session is reflected). No unauthenticated stream URL SHALL ever be opened from the UI.

#### Scenario: Live run streams while signed in
- **WHEN** an authenticated user starts a research session and watches it
- **AND THEN** the SSE connection opens with the token query parameter and events (PROGRESS, CONTENT, REPORT_CHUNK, REPORT_DONE, STEP_COMPLETE, ERROR) arrive exactly per the existing protocol

#### Scenario: Reconnect after drop keeps working
- **WHEN** an active stream drops and the service reconnects with backoff
- **AND THEN** each reconnection URL still carries a valid token and streaming resumes without re-login

### Requirement: Research routes require authentication
All research routes (`/research/new`, `/research/history`, `/research/history/:sessionId`, `/research/:sessionId`) SHALL be guarded: unauthenticated navigation (direct entry, deep link, or reload) SHALL redirect to `/login`; while the boot-time session check is still running, navigation SHALL wait for it to finish rather than flashing a redirect. The `/login` route SHALL redirect already-authenticated users to `/research/new`.

#### Scenario: Deep link while signed out
- **WHEN** a signed-out user opens `/research/history/<id>` directly
- **AND THEN** they are redirected to `/login` and no research data is requested

#### Scenario: Signed-in user visits /login
- **WHEN** an authenticated user navigates to `/login`
- **AND THEN** they are redirected to `/research/new` instead of seeing the form

### Requirement: Session expiry and logout
A `401` response from any non-auth endpoint SHALL clear the stored token, stop active streaming/polling, and navigate to `/login` with an indication that the session expired (e.g. `?reason=expired` rendered as a banner). A 401 from `/api/auth/**` SHALL NOT trigger this flow. Logout (toolbar action) SHALL clear the stored token, stop active streaming/polling, and return the user to `/login`.

#### Scenario: Token expires mid-session
- **WHEN** the user's JWT expires while they are using the app and a request (or the polling fallback after an SSE drop) receives 401
- **AND THEN** the token is cleared, streaming stops, and the login screen shows a session-expired banner; no page renders stale data or repeated error toasts

#### Scenario: Manual logout
- **WHEN** an authenticated user clicks Logout in the toolbar
- **AND THEN** the token is removed from storage, any live stream/polling is stopped, and the login screen is shown; subsequent navigation to research routes redirects to `/login`

## UNCHANGED Requirements
The backend wire contract (Phase 1 endpoints, payloads, status codes, `{"error": ...}` shapes), the SSE event protocol, research pipeline behavior, proxy configuration (`/api` → :8080) and CORS configuration are unchanged by this delta. Once authenticated, all existing UI features — start research, live streaming with reconnect/polling fallback, history list/search/detail, follow-up, single and bulk delete — behave exactly as before Phase 1. Per-user data isolation (Phase 3) and RBAC UI (Phase 5) remain out of scope: any authenticated user still sees every session until Phase 3 lands.
