# Spec — Boot Session Restore

## Requirement: Only a rejected token ends the session

When `GET /api/auth/me` fails during boot, the app SHALL treat only a 4xx response as a rejected token.
A transport failure (`status === 0`) or a server fault (5xx) SHALL leave the stored token in
`localStorage` and SHALL NOT set the expired flag.

#### Scenario: Backend is unreachable at boot

- **GIVEN** a stored token and a backend that cannot be reached
- **WHEN** `restoreSession()` runs
- **THEN** the token remains in `localStorage`
- **AND** `restoreExpired` is false
- **AND** `authStatus` is `anonymous`

#### Scenario: Token was rejected by the server

- **GIVEN** a stored token the server answers with 401 for
- **WHEN** `restoreSession()` runs
- **THEN** the token is removed from `localStorage`
- **AND** `restoreExpired` is true

### Requirement: Guarded routes redirect without a false banner

After an unreachable-boot failure, guarded research routes SHALL redirect to `/login` with no `reason`
query parameter; after a rejected token they SHALL redirect to `/login?reason=expired`.

#### Scenario: Login screen copy

- **WHEN** the user lands on `/login` after the backend was unreachable
- **THEN** the "Session expired — please sign in again." banner is not rendered
