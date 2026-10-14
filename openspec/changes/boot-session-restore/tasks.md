# Tasks — boot-session-restore

## 1. Distinguish rejection from unreachability

- [x] 1.1 `restoreSession()` error handler: `rejected = err.status >= 400 && err.status < 500`.
- [x] 1.2 Only `rejected` clears `localStorage` and sets `restoreExpired`.
- [x] 1.3 Transport/5xx failures log and keep the token; `restoreExpired` stays false.
- [x] 1.4 Both paths still set `currentUser = null` and `authStatus = 'anonymous'` so guarded routes
      redirect (plain `/login`, no expired banner).

## 2. Still open

- [ ] 2.1 Karma tests for `restoreSession()` (offline → token retained; 401 → token cleared). The UI
      currently has no test harness wired (`no test script in package.json`), so this needs the harness
      first — see `openspec/changes/add-ui-tests/`.
- [ ] 2.2 Retry the boot validation once when the backend later answers without a page reload.
