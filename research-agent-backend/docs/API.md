# Research Agent Backend — API Reference

## Overview

The backend exposes a RESTful API for managing research sessions and an SSE (Server-Sent Events) endpoint for real-time progress streaming. All paths are prefixed with `/api`.

---

## Authentication

No authentication is implemented in the current version. The service relies on CORS restrictions to limit access to authorized origins (`http://localhost:4200`, `http://127.0.0.1:4200` for development).

---

## Error Responses

| Status Code | Meaning |
|-------------|---------|
| 400 | Bad Request — invalid input, session not in expected state (e.g., cancel a non-PROCESSING session, bulk delete with empty body) |
| 401/403 | Not applicable — no auth implemented |
| 404 | Not Found — research session does not exist |
| 409 | Conflict — trying to delete a PROCESSING session (single or bulk) |
| 500 | Internal Server Error — LLM call failure or unexpected backend error |

---

## REST Endpoints

### `POST /api/research` — Start New Research Session

Starts a new research task. The response is returned **immediately** with status `PROCESSING`; the session completes asynchronously via SSE events.

- **Request Body**: `ResearchRequest`
  - `topic` (string, required) — The research topic to investigate
  - `maxIterations` (integer, optional, default=3) — Maximum number of sub-topic iterations to process
  - `subTopicCount` (integer, optional, default=5) — Number of sub-topics to generate from the LLM breakdown
  - `streamingEnabled` (boolean, optional, default=true) — Whether SSE streaming should be enabled

- **Response**: `201 Created` — `ResearchResponse`
  ```json
  {
    "id": "a3f1b2c4-d5e6-f789-abcd-ef0123456789",
    "topic": "AI in Healthcare",
    "status": "PROCESSING"
  }
  ```

---

### `GET /api/research/{sessionId}` — Get Research Session Status

Retrieves the current state of a research session. Reflects real-time progress as SSE events are processed.

- **Path Parameter**: `sessionId` (UUID, required)

- **Response**: `200 OK` — `ResearchResponse`
  ```json
  {
    "id": "a3f1b2c4-d5e6-f789-abcd-ef0123456789",
    "topic": "AI in Healthcare",
    "status": "PROCESSING",
    "createdAt": "2026-06-03T10:00:00",
    "completedAt": null,
    "finalReport": null,
    "steps": [
      {
        "orderIndex": 1,
        "type": "BREAKDOWN",
        "status": "COMPLETED"
      },
      {
        "orderIndex": 2,
        "type": "SUBTOPIC",
        "status": "RUNNING"
      }
    ]
  }
  ```

- **Response**: `404 Not Found` — Session not found

---

### `DELETE /api/research/{sessionId}` — Cancel Research Session

Cancels a running research session. Only works while the session is in `PROCESSING` state.

- **Path Parameter**: `sessionId` (UUID, required)

- **Response**: `204 No Content`
- **Response**: `400 Bad Request` — Session not found or not in PROCESSING state

---

### `GET /api/research/history` — Get Research History

Returns a paginated list of all research sessions ordered by creation date (descending).

- **Query Parameters**:
  - `page` (integer, optional, default=0) — Page number (zero-based)
  - `size` (integer, optional, default=20) — Items per page

- **Response**: `200 OK` — Spring Data `Page<ResearchSession>`
  ```json
  {
    "content": [
      {
        "id": "a3f1b2c4-d5e6-f789-abcd-ef0123456789",
        "topic": "AI in Healthcare",
        "status": "COMPLETED",
        "createdAt": "2026-06-03T10:00:00",
        "updatedAt": "2026-06-03T10:05:00",
        "completedAt": "2026-06-03T10:05:00"
      }
    ],
    "totalPages": 1,
    "totalElements": 1
  }
  ```

---

### `GET /api/research/history/search` — Search Research History by Topic

Searches research sessions by topic name (case-insensitive). Returns paginated results.

- **Query Parameters**:
  - `query` (string, required) — Search term to match against session topics
  - `page` (integer, optional, default=0) — Page number (zero-based)
  - `size` (integer, optional, default=20) — Items per page

- **Response**: `200 OK` — Spring Data `Page<ResearchSession>`

---

### `GET /api/research/history/{sessionId}` — Get Historical Session Detail

Returns a single research session with full step details. Uses `ResearchSessionDetailDTO`.

- **Path Parameter**: `sessionId` (UUID, required)

- **Response**: `200 OK` — `ResearchSessionDetailDTO`
  ```json
  {
    "id": "...",
    "topic": "AI in Healthcare",
    "status": "COMPLETED",
    "prompt": "...",
    "finalReport": "# Report content...",
    "createdAt": "2026-06-03T10:00:00",
    "updatedAt": "2026-06-03T10:05:00",
    "completedAt": "2026-06-03T10:05:00",
    "steps": [
      {
        "orderIndex": 1,
        "type": "BREAKDOWN",
        "status": "COMPLETED",
        "content": "[{\"id\":\"1\",\"title\":\"...\"}]"
      }
    ]
  }
  ```

- **Response**: `404 Not Found` — Session not found

---

### `DELETE /api/research/history/{sessionId}` — Delete Single Historical Session

Deletes a single historical session (and its steps via cascade). Only works on non-running sessions (COMPLETED, FAILED, CANCELLED). Returns 409 Conflict if the session is still PROCESSING.

- **Path Parameter**: `sessionId` (UUID, required)
- **Response**: `204 No Content` — Deleted successfully
- **Response**: `404 Not Found` — Session not found
- **Response**: `409 Conflict` — Session is still PROCESSING

---

### `POST /api/research/history/bulk-delete` — Bulk Delete Sessions

Bulk deletes multiple sessions in a single operation. All-or-nothing rollback — if any session is invalid or still PROCESSING, none are deleted. Returns 409 Conflict with rolled-back state.

- **Request Body**: Array of UUIDs
  ```json
  ["uuid-1", "uuid-2", "uuid-3"]
  ```

- **Response**: `204 No Content` — All sessions deleted successfully
- **Response**: `400 Bad Request` — Empty or null request body
- **Response**: `409 Conflict` — One or more sessions are still PROCESSING (all rolled back)

---

### `GET /api/research/{sessionId}/report` — Get Final Report

Retrieves the synthesized research report for a completed session. Falls back to generating from collected sub-topic findings if no final report exists.

- **Path Parameter**: `sessionId` (UUID, required)

- **Response**: `200 OK` — Raw markdown text of the final report
- **Response**: `400 Bad Request` — Session not yet COMPLETED or not found
- **Response**: `404 Not Found` — No report available and no sub-topic findings to generate from

---

### `POST /api/research/{sessionId}/followup` — Submit Follow-Up Question

Submits a follow-up question about the research findings of a completed session. The LLM answers based on the previous research report (truncated to 2000 chars) and the new question.

- **Path Parameter**: `sessionId` (UUID, required)

- **Request Body**: `FollowUpRequest`
  ```json
  {
    "question": "How does AI-driven diagnostics compare to traditional methods?"
  }
  ```

- **Response**: `200 OK` — The LLM-generated answer as plain text
- **Response**: `404 Not Found` — Session not found

---

## SSE Endpoint

### `GET /api/research/stream/{sessionId}` — Subscribe to Real-Time Progress Events

Establishes a Server-Sent Events connection for real-time progress updates. Note: This endpoint uses a standalone SseEmitter in `ResearchStreamController` that is **not wired** into the actual streaming service (`ResearchStreamingService`). The frontend connects here but events are sent via `ResearchOrchestratorService` → `ResearchStreamingService.sendReportChunk()` etc.

- **Path Parameter**: `sessionId` (UUID, required)
- **Produces**: `text/event-stream`
- **Timeout**: 10 minutes (`600000ms`) — the SseEmitter closes after this period of inactivity

---

## SSE Event Types

All events use the following structure:

```json
{
  "type": "<EVENT_TYPE>",
  "sessionId": "<uuid>",
  "payload": "<event-specific data>"
}
```

### `PROGRESS`

Sent when a new research step begins or a sub-topic transitions. The payload is a description string (e.g., `"Researching: Sub-topic A"` for start, `"Completed research on: Sub-topic A"` for completion).

### `STEP_COMPLETE`

Sent when a step finishes processing. Payload contains metadata about the completed step — may be a string (step name), number (index/stepNumber), or object with keys like `stepName`, `name`, `index`, `stepNumber`. Frontend uses multi-strategy matching to identify which step was completed.

### `CONTENT`

Sent during streaming of sub-topic findings (raw content chunks). Payload is a text chunk from the LLM response.

### `REPORT_START`

Sent when the final report synthesis begins. Triggers frontend to inject "Generating Report" step into the steps list with IN_PROGRESS status and start a stall timer (90s without chunks → force polling fallback). No payload data needed.

### `REPORT_CHUNK`

Sent during streaming of the final research report. Payload is a raw text chunk that gets accumulated into the report content signal on the frontend. Each chunk also resets the stall timer.

### `REPORT_DONE`

Sent when the final report is complete. Payload contains the full synthesized report as markdown text. Updates session status to COMPLETED and sets isStreaming to false.

### `ERROR`

Sent if an error occurs during processing. Payload is the error message string. Sets session status to FAILED on frontend.

---

## Data Models

See [MODELS.md](./MODELS.md) for detailed model definitions with all fields, types, and constraints.
