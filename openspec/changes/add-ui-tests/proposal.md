# Add a UI test harness

## Why

The Angular app has karma configured in `research-agent-ui/package.json` but **zero** `.spec.ts` files,
no `karma` entry in `dependencies`, and no `test` script — so no UI behaviour is covered by anything the
CI runs. Every regression in the review of this repo (SSE reconnect/polling fallback, ownership 404s,
boot session restore, step labels) is currently verified only by hand-clicking the app.

## What changes

- Add the karma dependency and a `test` script (`npm test`) so `deploy.yml` / `ci.yml` can run UI tests.
- Cover the seams that carry the logic rather than the templates:
  `research.service.ts` (SSE connect/reconnect/poll fallback), `research-history.service.ts` (page
  mapping), `auth.service.ts` (restore rules), `getStepName` labelling.

## Impact

- CI gains a UI step; the deploy workflow stays UI-build-only if the test step is kept in `ci.yml`.
- This is a pending change — no code written yet in this branch.
