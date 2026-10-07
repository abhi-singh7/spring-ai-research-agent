# Design: Frontend Login & Authorization (Phase 2)

## Decision 1: token storage in `localStorage`, not sessionStorage or memory

Research runs last minutes to hours and users routinely refresh mid-run (the UI already supports re-attaching to a live session from history detail). With Phase 1's 8 h default token expiry, the desired UX is "sign in once per work session." Options:

| Option | Verdict |
|--------|---------|
| In-memory only | Rejected — every refresh forces re-login mid-research |
| `sessionStorage` | Rejected — closing and reopening the tab (common) drops the session |
| **`localStorage`** | Chosen — survives refresh and browser restart until the token expires; logout removes it explicitly |

Accepted trade-off: a localStorage token is readable by any JavaScript running on the page (XSS exposure). For the current single-user local deployment with no public ingress this is acceptable; if the app ever gains public hosting, move to httpOnly-cookie storage and re-enable CSRF (already tracked in Phase 1's Deferred list).

The key is a fixed constant (`ra-auth-token`) owned exclusively by `AuthService` — no other code reads or writes it.

## Decision 2: Bearer header via one `HttpInterceptor`, not per-service headers

All REST traffic already flows through `HttpClient` (`ResearchService`, `ResearchHistoryService`). A single interceptor registered in the existing `HTTP_INTERCEPTORS` multi-provider covers every current and future endpoint uniformly, with zero changes to the services' call sites. It is registered **before** `DebugHttpInterceptor` so the debug log shows requests as actually sent (header attached).

The interceptor attaches the header only when a token exists — `/api/auth/login` and `/api/auth/register` must work anonymously, and attaching a stale token to them would be noise. The important asymmetry is on the **response** side: a `401` from `/api/auth/**` is a normal business outcome (wrong password) and must render inline in the form; a `401` from anywhere else means the session died and triggers logout + redirect (Decision 5).

## Decision 3: SSE token appended inside `connectSse()`, not in component URLs

`EventSource` cannot set headers, so the stream URL carries `?token=<jwt>` (backend contract from Phase 1). `ResearchService.connectSse()` is the single place an EventSource URL is built — initial connects and every reconnection call it (reconnect passes only the session id, no URL). Appending the token there means:

- reconnects never lose the token (a component-side URL would be dropped by `connectSse(sessionId)` on retry),
- a fresh login mid-session is picked up automatically on the next connect (token read from `AuthService` at connect time, not captured once at construction).

The guard ensures users can only reach streaming screens while authenticated, so an unauthenticated EventSource can never be constructed in practice.

## Decision 4: signals-based auth state machine + functional guard

```
boot ──► checking ──restoreSession()──► authenticated   (token valid per /api/auth/me)
                        └──────────────► anonymous        (no token, or /me rejected it)
authenticated ──logout()/401──► anonymous
```

`AuthService` exposes the state as signals (`authStatus`, `currentUser`) plus a single cached promise/observable that resolves when `restoreSession()` finishes. The guard is a functional `CanActivateFn` (Angular 18 idiom — no class, matches the standalone-component convention): while `'checking'` it awaits that promise (cached, so parallel navigations don't stack requests); `'anonymous'` → `router.navigate(['/login'])`; `'authenticated'` → `true`. Starting `restoreSession()` from `AppComponent`'s constructor guarantees the check begins before any route renders.

The `/login` route is unguarded and itself redirects authenticated users to `/research/new` — a signed-in user never sees the form, and an anonymous user deep-linking to e.g. `/research/history/xyz` lands on `/login` (post-login navigation goes to `/research/new`; redirect-back-to-originating-route is deferred polish, not required here).

## Decision 5: 401 handling lives in the interceptor; SSE expiry rides the polling fallback

One rule, one place: **any non-auth endpoint returning 401 ⇒ session over.** The interceptor calls `authService.logout()` (clears storage, stops SSE/polling) and navigates to `/login?reason=expired`; the login screen shows a "Session expired — please sign in again" banner when that query param is present.

The SSE edge case: `EventSource` cannot read HTTP status or bodies, so an expired token on the stream surfaces as an opaque `error` event. The existing resilience machinery handles it without new logic — backoff reconnects (3 attempts) fail the same way, then the polling fallback starts and its **first poll is immediate**, so the 401 reaches the interceptor within one polling step of exhausting reconnection attempts. Spec'd as: stream-token expiry redirects to login no later than the first poll after reconnection attempts are exhausted.

## Decision 6: the login screen doubles as registration

Phase 1 ships `POST /api/auth/register` (201 + auto-login token). Without a UI path, the very first user would have to register via curl — bad onboarding for the product's primary use case. A single `/login` screen with a Login/Register toggle keeps the scope to one route and one component while making account creation self-service. Register success uses the 201 response's token exactly like login does (auto-login is already backend behavior).

## Decision 7: no proxy, CORS, or environment changes

`environment.apiUrl` is `''` in both environments: dev traffic rides the existing `proxy.conf.json` (`/api` → `localhost:8080`) and production is same-origin behind the jar. `/api/auth/**` already falls under the proxied `/api` prefix, and `WebConfig`'s MVC CORS for `localhost:4200` is unchanged. Nothing to add here — recorded so it isn't re-litigated later.

## Wire contract (consumed unchanged from Phase 1)

```
POST /api/auth/register   {username, password}            → 201 {token, username} | 409/400 {error}
POST /api/auth/login      {username, password}            → 200 {token, username} | 401 {error}
GET  /api/auth/me         Authorization: Bearer <jwt>     → 200 {username, role, createdAt} | 401 {error}
All /api/research/**      Authorization: Bearer <jwt>     (interceptor-attached)
GET /api/research/stream/{sessionId}?token=<jwt>          (EventSource URL, built in connectSse)
```

No new endpoints, no payload changes. All auth-failure bodies share the Phase 1 shape `{"error": "..."}` and are rendered verbatim inline on the login screen.
