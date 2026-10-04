# Proposal: Frontend Login & Authorization (Phase 2)

## Why (Motivation)
Phase 1 (`add-jwt-authentication`) made every `/api/research/**` endpoint require a Bearer JWT and the SSE stream require `?token=`. The Angular UI is auth-unaware, so **every API call from the deployed frontend now fails with 401** — the accepted transitional state documented in Phase 1's proposal. This change closes that gap:

- A login screen (with first-time registration, since Phase 1 ships `POST /api/auth/register` and there is otherwise no UI path to create an account)
- Token persistence and session restore on page load
- An `HttpInterceptor` attaching `Authorization: Bearer <jwt>` to all API requests
- The JWT appended as `?token=` on the EventSource URL (EventSource cannot set headers)
- Route guards so no research screen renders without a valid session
- Session-expiry handling (401 → clear token → back to login with an explanation)
- Current username + logout in the toolbar

**Scope boundary:** backend is untouched (this change consumes Phase 1's wire contract exactly as shipped). Per-user data isolation (`ownerId`) remains Phase 3; RBAC UI (ADMIN-only actions) remains Phase 5. No frontend test infrastructure is added — the repo has no spec/karma setup and verification is build + manual walkthrough (see tasks.md).

## What Changes

### Added
- **`core/services/auth.service.ts`** — signals-based auth state: `authStatus` (`'checking' | 'authenticated' | 'anonymous'`), `currentUser` (`{username, role} | null`). Owns the token in `localStorage` (fixed key). Methods: `login(username, password)`, `register(username, password)` (auto-login on 201), `logout()`, `restoreSession()` (validates a stored token via `GET /api/auth/me` at boot; resolves a single cached promise so route guards can wait on it), `getToken()`.
- **`core/interceptors/auth.interceptor.ts`** — attaches the Bearer header to every request under `/api/` when a token is present. On a `401` response from any endpoint **except** `/api/auth/**` (where 401 is a normal business error, e.g. wrong password), clears the session and redirects to `/login?reason=expired`.
- **`features/login/login.component.ts`** — standalone component on route `/login`: login form with a register toggle (one screen, two modes). Inline errors rendered from the backend's `{"error": ...}` body; a banner when arriving with `?reason=expired`. Successful login/register stores the token and navigates to `/research/new`.
- **`authGuard`** — functional `CanActivateFn` (defined in `app.routes.ts` or a small `core/guards/auth.guard.ts`): waits for `restoreSession()` while status is `'checking'`, redirects unauthenticated users to `/login`, allows authenticated ones.

### Modified
- **`app.routes.ts`** — new unguarded `/login` route; `canActivate: [authGuard]` on all four research routes (`research/new`, `research/history`, `research/history/:sessionId`, `research/:sessionId`). The existing catch-all redirect to `research/new` is kept (it bounces to `/login` via the guard when anonymous).
- **`app.config.ts`** — registers `AuthHttpInterceptor` in the `HTTP_INTERCEPTORS` multi-provider, before `DebugHttpInterceptor` (so debug logs show the authenticated request).
- **`core/services/research.service.ts`** — `connectSse()` appends `?token=<jwt>` from `AuthService` to the stream URL. This is the single choke point: initial connects and all reconnections (`connectSse(sessionId)` without a URL) inherit the token automatically, and a fresh login mid-session is picked up on reconnect.
- **`app.component.ts`** — toolbar shows the signed-in username and a Logout button when authenticated; logout clears storage, stops any active SSE/polling, and navigates to `/login`.

### Removed
- Nothing. No backend change, no route removals, no payload or SSE event changes.

## Impact Assessment
| File | Change Type | Risk Level |
|------|-------------|------------|
| `core/services/auth.service.ts`, `core/interceptors/auth.interceptor.ts`, `features/login/*` | Added | Low — additive; the 401-redirect logic is the only new cross-cutting behavior |
| `app.routes.ts` | Modified (guard + login route) | Medium — guard ordering/timing at boot is the main risk (must not deadlock while `'checking'`) |
| `core/services/research.service.ts` (`connectSse`) | Modified (token query param) | Low — one URL construction site; SSE protocol unchanged |
| `app.config.ts`, `app.component.ts` | Modified | Low — provider registration + toolbar markup |

Behavioral guarantees preserved: once authenticated, every existing feature (start research, live streaming with reconnect/polling fallback, history list/search/detail, follow-up, single/bulk delete) behaves exactly as before Phase 1. The backend wire contract is consumed unchanged; no new API surface is introduced.
