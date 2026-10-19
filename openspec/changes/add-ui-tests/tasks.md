# Tasks — add-ui-tests (pending — not implemented in this branch)

## 1. Harness

- [ ] 1.1 Add `karma` to `research-agent-ui/package.json` `dependencies`.
- [ ] 1.2 Add a `test` script (`ng test` / karma runner) and wire it into `.github/workflows/ci.yml`.

## 2. Seams to cover

- [ ] 2.1 `research.service.ts` — reconnect with exponential backoff, polling fallback, 90 s stall timer.
- [ ] 2.2 `auth.service.ts` — restore rules: 401 clears the token, transport failure retains it
      (see `openspec/changes/boot-session-restore`).
- [ ] 2.3 `research-history.service.ts` — page envelope mapping and the summary row shape
      (see `openspec/changes/history-summary-payload`).
- [ ] 2.4 `getStepName` — every emitted `StepType` has a label (see
      `openspec/changes/step-label-vocabulary`).
