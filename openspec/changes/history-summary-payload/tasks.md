# Tasks — history-summary-payload

## 1. Summary DTO

- [x] 1.1 `ResearchSessionSummaryDTO` — id, topic, status, owner, `createdAt`/`updatedAt`/`completedAt`,
      `reportAvailable`, `stepCount`. No `finalReport`, no `prompt`, no step bodies.
- [x] 1.2 Field names mirror the entity so the Angular `ResearchHistoryItem` model is unchanged.

## 2. Controller shaping

- [x] 2.1 `getHistory` and `searchHistory` return `Page<ResearchSessionSummaryDTO>`.
- [x] 2.2 `summarize(page)` maps with `Page#map`, preserving page number / size / totalElements.
- [x] 2.3 The orchestrator signature stays `Page<ResearchSession>` — shaping lives at the HTTP boundary.

## 3. Tests

- [x] 3.1 `getHistory_shouldNotShipReportTextInRows` — a session with a 4 000-char report serializes with
      `reportAvailable: true` and **no** `finalReport` key in the row.
- [x] 3.2 Existing history tests still assert `$.content` is an array (unchanged page envelope).

## 4. Still open

- [ ] 4.1 Use `reportAvailable` in the history list (offer "view report" only when one exists) and
      `stepCount` as a progress hint.
- [ ] 4.2 A `steps` count facet on the detail DTO's header row so the UI can show "8 steps" without
      walking the array.
