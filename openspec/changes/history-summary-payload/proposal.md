# History rows carry summaries, not sessions

## Why

`GET /api/research/history` and `/history/search` serialize the `ResearchSession` **entity**. Steps and
follow-ups are already `@JsonIgnore`d, but `finalReport` and `prompt` are not — so every history page
ships the full markdown report of every session on that page. With 20 rows × multi-thousand-word
reports that is hundreds of kilobytes per page load, and the list view renders neither field
(`features/research-history` shows topic/status/timestamps only; the report is fetched separately by
`history-detail` through `GET /api/research/history/{id}`).

## What changes

- New `ResearchSessionSummaryDTO` — id, topic, status, owner, timestamps, `reportAvailable` (bool),
  `stepCount` (int). No report text, no prompt, no step bodies.
- `ResearchController.getHistory` / `searchHistory` map the entity page to that DTO page
  (`Page#map` preserves the page window, sort and totals) and return `Page<ResearchSessionSummaryDTO>`.
- The orchestrator keeps returning `Page<ResearchSession>` — the shaping happens at the HTTP boundary,
  so the service API and its tests are untouched.

## Impact

- Field names mirror the entity, so the Angular `ResearchHistoryItem` model and the history list view
  keep working unchanged; nothing in the UI read `finalReport` off a list row.
- `reportAvailable` and `stepCount` are additive: the list can now offer a "view report" action and show
  progress without shipping bodies (the UI does not use them yet).
- `GET /api/research/history/{id}` still returns the full `ResearchSessionDetailDTO` (steps + report +
  follow-ups) — that is where the report is loaded from.
