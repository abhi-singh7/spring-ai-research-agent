# Tasks: Frontend Login & Authorization (Phase 2)

## Implementation
- [x] `core/services/auth.service.ts`: signals `authStatus` (`checking|authenticated|anonymous`) + `currentUser`; fixed-key localStorage token; `login()`, `register()` (auto-login on 201), `logout()`, `restoreSession()` via `GET /api/auth/me` with a single cached completion promise, `getToken()`
- [x] `core/interceptors/auth.interceptor.ts`: attach `Authorization: Bearer <token>` to `/api/**` requests when a token exists; on 401 from any non-`/api/auth/**` endpoint → `logout()` + navigate to `/login?reason=expired`; 401s from auth endpoints pass through for inline handling
- [x] `app.config.ts`: register `AuthHttpInterceptor` in the `HTTP_INTERCEPTORS` multi-provider before `DebugHttpInterceptor`
- [x] `features/login/login.component.ts` (standalone, Material): login form + registration toggle; inline errors from `{"error": ...}` bodies; "session expired" banner for `?reason=expired`; success → store token → navigate `/research/new`; authenticated users redirected away from the route
- [x] Functional `authGuard` (`CanActivateFn`): await cached restore promise while `checking`, redirect anonymous → `/login`, allow authenticated; apply via `canActivate` on all four research routes in `app.routes.ts`; add unguarded `/login` route (keep existing catch-all)
- [x] `core/services/research.service.ts`: `connectSse()` appends `?token=<jwt>` from `AuthService` at connect time (covers initial connects and all reconnects)
- [x] `app.component.ts`: toolbar shows signed-in username + Logout button when authenticated; logout stops SSE/polling (`disconnectSse`/`stopPolling`) before navigating to `/login`

## Verification (build + manual walkthrough — no frontend test infra exists in this repo)
- [x] `ng build` passes clean under strict mode with no new template/type errors
- [x] Fresh browser, signed out: app lands on `/login`; deep link `/research/history/<id>` redirects to `/login` without firing research requests
- [x] Register a brand-new user from the UI → auto-login → lands on `/research/new`; duplicate (any case) and weak-password attempts show inline 409/400 errors
- [x] Logout → login with wrong password shows inline error; correct credentials sign in
- [x] Reload mid-session: stays signed in, previous route renders without re-login
- [x] Start a live research run: SSE connects with `?token=` (visible in DevTools), events stream; kill/restart the backend mid-run and confirm reconnects keep the token, then polling fallback takes over
- [x] History list/search, history detail, follow-up, single delete, bulk delete all work while signed in
- [x] Force an expired/invalid stored token (e.g. edit localStorage or use a short-lived test secret): app boots to `/login` with the session-expired banner; expiry mid-session redirects via the polling-fallback 401 without rendering stale data

## Deferred (out of scope for this change)
- [ ] Redirect back to the originally requested route after login (currently lands on `/research/new`)
- [ ] Refresh-token / remember-me mechanics (backend defers revocation + refresh in Phase 1's Deferred list)
- [ ] httpOnly-cookie token storage if XSS hardening is ever required for public hosting
- [ ] Frontend test infrastructure (karma/spec setup) — add via a separate change if desired
- [ ] Per-user data isolation (`ownerId`) — Phase 3; RBAC UI (ADMIN-only actions) — Phase 5
