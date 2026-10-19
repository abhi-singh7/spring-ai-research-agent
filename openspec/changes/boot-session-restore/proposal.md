# Boot session restore must not confuse "offline" with "expired"

## Why

`AuthService.restoreSession()` validates a stored token with `GET /api/auth/me` at boot. Its error
handler treats **any** failure as "the token no longer validates", deletes it from `localStorage` and
sets `restoreExpired`, which the guard turns into `/login?reason=expired` and the login screen renders
as "Session expired — please sign in again."

That is wrong for the common case of the backend being down or unreachable (transport error,
`status === 0`, or a 5xx). The user's JWT is fine; they are told their session ended and must re-enter
credentials — and the token is gone, so nothing is restored when the backend comes back.

## What changes

- The error handler splits on HTTP status: only a **4xx rejection** clears the token and sets
  `restoreExpired`. A transport failure or server fault keeps the token in `localStorage`, leaves
  `restoreExpired` false, and still marks the boot as `anonymous` (routes redirect to plain `/login`).
- Next boot re-validates the retained token, so a reload after the backend recovers signs the user back
  in.

## Impact

- No backend change. No new endpoint.
- The interceptor's 401 rule (`auth.interceptor.ts`) already ignores non-401 errors, so this makes boot
  consistent with in-flight behaviour.
- Worst case retained: a stale-but-unreachable token stays in `localStorage` until the next successful
  validation, where a 401 clears it.
