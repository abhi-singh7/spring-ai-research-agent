# Spec — History Summary Payload

## Requirement: History rows are summaries

History list and search responses SHALL serialize each session as `ResearchSessionSummaryDTO`, never the
`ResearchSession` entity. A row SHALL contain `id`, `topic`, `status`, `owner`, `createdAt`, `updatedAt`,
`completedAt`, `reportAvailable`, `stepCount` and SHALL NOT contain `finalReport`, `prompt`, `steps`, or
`followUps`.

#### Scenario: A completed session with a long report appears in the list

- **GIVEN** a session whose `finalReport` is non-empty
- **WHEN** the caller requests `GET /api/research/history`
- **THEN** the row for that session has `reportAvailable: true`
- **AND** the row has no `finalReport` key

#### Scenario: Paging envelope is preserved

- **GIVEN** 42 owned sessions and a request for page 2 of size 20
- **WHEN** the history endpoint responds
- **THEN** `content` holds the 2 remaining rows in `createdAt` DESC order
- **AND** `totalElements` is 42 and `number`/`size`/`totalPages` match the request

### Requirement: Detail endpoints still ship the report

`GET /api/research/history/{sessionId}` SHALL keep returning `ResearchSessionDetailDTO` including `steps`,
`finalReport`, and `followUps`, subject to the ownership gate.

#### Scenario: Report is loaded from the detail endpoint

- **WHEN** the UI opens a history row and navigates to `/research/history/{id}`
- **THEN** the detail response carries the full `finalReport` text
