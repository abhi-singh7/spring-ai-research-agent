# Proposal: Fix SSE Streaming & Polling Content Recovery

## Why

Two bugs prevent users from seeing research results in the active session view:

1. **No streaming at all** — The SSE connection silently fails with no fallback to polling, so no chunks ever reach the frontend. The `connectSse()` call is wrapped in a try/catch that can never catch async EventSource errors, leaving users stuck on "Processing..." indefinitely even after the backend finishes research.

2. **Truncated/broken response after completion** — When POLLING detects COMPLETED status (either as fallback or when SSE fails), it syncs steps from REST but does not populate `_reportContentSignal` with `finalReport`. Combined with Bug #1, this means users see empty or partial content instead of the full report stored in the database.

## What Changes

- Add SSE connection timeout detection in `connectSse()` — if the connection never opens within ~3 seconds, fall back to polling
- Fix the try/catch fallback in `startResearch()` to handle async EventSource failures properly
- When POLLING detects terminal state transition (COMPLETED/FAILED/CANCELLED), also sync `_reportContentSignal` from REST's `finalReport` field
- Preserve existing step descriptions when POLLING syncs steps from REST instead of blindly overwriting them

## Impact

| Area | Impact |
|------|--------|
| Frontend — `research.service.ts` | SSE connection timeout, polling content recovery, step description preservation |
| Backend | No changes needed (REST already returns `finalReport`) |
| User experience | Full report visible in active session view regardless of SSE connectivity |

## Assumptions Made

- The backend's `/api/research/{sessionId}` endpoint correctly returns `finalReport` for completed sessions — verified by reading `ResearchController.getStatus()` which sets `response.setFinalReport(session.getFinalReport())`
- POLLING currently only syncs steps at terminal state transitions (not during PROCESSING) — confirmed in `pollStatus()` lines 217-243
- The history view (`HistoryDetailComponent`) works correctly because it uses `getHistoricalSession()` which has full data — this is the ground truth for what "complete response" looks like
