# Design: JWT Authentication (Phase 1)

## Decision 1: stateless JWT, not server-side sessions

The app is a single Spring Boot service in front of MongoDB; there is no session store to speak of and the SSE client reconnects independently. A signed bearer token keeps every node's decision local (no shared session state), survives browser tab restarts/reconnects without re-login logic on the server, and makes the auth model trivially testable (a token in a header). Costs accepted: tokens live until expiry (no revocation list in Phase 1 — bounded by an 8 h default expiry; see Deferred for refresh/revocation), and the token must travel in the URL for SSE (Decision 5).

## Decision 2: `nimbus-jose-jwt`, not jjwt

The project runs **Jackson 3** (`tools.jackson.*` under Spring Boot 4). The popular `jjwt` library's Jackson integration (`jjwt-jackson`) is built on the **Jackson 2** `ObjectMapper` — adding it would put two major JSON-lib generations on one classpath purely for claim (de)serialization. `com.nimbusds:nimbus-jose-jwt` is self-contained (no dependency on either Jackson generation), is the library Spring Security's own OAuth2/JWT support uses internally, and exposes exactly the small surface we need:

```java
// issue
JWTSigner signer = new MACSigner(secretBytes);            // HS256
JWTClaimsSet claims = JWTClaimsSet.builder()
    .subject(username)
    .claim("role", role)
    .issueTime(new Date())
    .expirationTime(expiry)
    .issuer(issuer)
    .build();
String token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims).toString();

// validate
SignedJWT.parse(token).verify((JWSVerifier) signer);      // throws on bad signature
```

Token format stays standard RFC 7519 compact serialization, so any future tooling (curl scripts, other services) interoperates without knowing our library.

## Decision 3: HS256 with a configured secret; fail fast on weak config

- Symmetric HS256 is sufficient for a single-service deployment (no inter-service RS256 verification needed yet).
- `app.security.jwt.secret` supports `${JWT_SECRET:...}` env override, ships with a **dev-only default** clearly marked in the yml, and `JwtService` validates ≥32 bytes at startup and throws otherwise — a misconfigured production secret fails boot instead of silently issuing weak tokens.
- `expiration-seconds: 28800` (8 h) balances "survives a work session" against token lifetime exposure.

## Decision 4: CSRF disabled, stateless session policy

With no auth-carrying cookies (tokens ride in `Authorization` headers / one query param), the classic CSRF attack surface does not exist; enabling CSRF protection would only break the API client. `SessionCreationPolicy.STATELESS` guarantees Spring never creates a `HttpSession` as a side effect. Trade-off recorded: if Phase 2+ ever moves to cookie-based auth (e.g. httpOnly cookie for SSE), CSRF protection must be re-enabled — noted in Deferred.

## Decision 5: SSE stream authenticates via `?token=` query param, validated in the handler

The browser's `EventSource` constructor accepts only a URL — it **cannot set an `Authorization` header** (and does not send custom headers on reconnect). Options considered:

| Option | Verdict |
|--------|---------|
| Permit `/api/research/stream/**` unauthenticated | Rejected — leaks live session data to anyone with the UUID |
| Cookie-based auth for SSE | Works, but drags in CSRF/session machinery (Decision 4) and changes the frontend storage model mid-project |
| **Query-param token, validated in `ResearchStreamController`** | Chosen: filter chain permits the path; the controller validates via the same `JwtService` and returns `401` JSON before opening the stream when the token is missing/invalid. One endpoint carries a URL-borne credential — accepted for a local deployment (no public ingress, no access-log exposure to third parties); short 8 h expiry bounds replay value |

The controller's return type becomes `ResponseEntity<SseEmitter>` so the 401 path is a normal JSON response; with a valid token the emitted SSE events are byte-identical to today's protocol.

## Decision 6: transitional UI state accepted, Phase 2 follows immediately

Phase 1 is backend-only by agreement. Until Phase 2 (login screen + `HttpInterceptor` attaching the Bearer header + `?token=` on the EventSource URL + route guards) merges, the deployed UI shows 401s. This is documented in the proposal and is acceptable because: the API is fully exercisable via curl/Postman for verification, the CI/CD pipeline deploys each phase automatically (window = one PR cycle), and no data or schema change makes the state irreversible.

## Decision 7: per-user data isolation deferred to Phase 3

Adding `ownerId` to `research_session`, scoping every query, and migrating existing documents is a separate, larger change with its own migration script. Until Phase 3, **any authenticated user can read/mutate any session** — an accepted risk for the current single-user local deployment, explicitly recorded here so it is not discovered later as an oversight.

## Decision 8: `role` field now, enforcement later (Phase 5)

`User.role` defaults to `USER` and is carried in the JWT `role` claim from day one, so Phase 5 RBAC (e.g. ADMIN-only bulk delete / global history) is purely additive: an authority check on existing endpoints — no schema or token-format change. Storing role in the document (not only the token) keeps `/me` and future admin queries honest after password changes.

## Wire contract (new API surface)

```
POST /api/auth/register   {username, password}            → 201 {token, username}
POST /api/auth/login      {username, password}            → 200 {token, username} | 401 {error}
GET  /api/auth/me         Authorization: Bearer <jwt>     → 200 {username, role, createdAt}

All existing /api/research/** endpoints:
    Authorization: Bearer <jwt>   required → 401 {error} when missing/invalid
GET /api/research/stream/{sessionId}?token=<jwt>          (SSE; 401 before stream opens)
```

Validation rules: `username` 3–32 chars, `[a-zA-Z0-9_]`, unique (case-insensitive via stored lowercase); `password` ≥ 8 chars. Duplicate registration → `409`. Login failure and all auth failures share one JSON shape: `{"error": "..."}` with `401` (registration validation errors use `400`).
