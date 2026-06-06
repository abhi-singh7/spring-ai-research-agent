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
| 400 | Bad Request — invalid input, session not in expected state (e.g., cancel a non-PROCESSING session) |
| 404 | Not Found — research session does not exist |
| 500 | Internal Server Error — LLM call failure or unexpected backend error |

---

## REST Endpoints

### `POST /api/research` — Start New Research Session

Starts a new research task. The response is returned **immediately** with status `PROCESSING`; the session will complete asynchronously via SSE events.

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
    "status": "PROCESSING",
    "createdAt": "2026-06-03T10:00:00Z",
    "updatedAt": "2026-06-03T10:00:00Z",
    "completedAt": null,
    "finalReport": null,
    "streamUrl": "",
    "steps": []
  }
  ```

---

### `GET /api/research/{sessionId}` — Get Research Session Status

Retrieves the current state of a research session. The response reflects real-time progress as SSE events are processed.

- **Path Parameter**: `sessionId` (UUID, required)

- **Response**: `200 OK` — `ResearchResponse`
  ```json
  {
    "id": "a3f1b2c4-d5e6-f789-abcd-ef0123456789",
    "topic": "AI in Healthcare",
    "status": "PROCESSING",
    "createdAt": "2026-06-03T10:00:00Z",
    "updatedAt": "2026-06-03T10:05:00Z",
    "completedAt": null,
    "finalReport": null,
    "streamUrl": "",
    "steps": [
      {
        "orderIndex": 1,
        "type": "BREAKDOWN",
        "status": "COMPLETED",
        "content": "[{\"id\":1,\"title\":\"Sub-topic A\",\"description\":\"...\"}]"
      },
      {
        "orderIndex": 2,
        "type": "SUBTOPIC",
        "status": "IN_PROGRESS",
        "content": null
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
        "createdAt": "2026-06-03T10:00:00Z",
        "updatedAt": "2026-06-03T10:05:00Z",
        "completedAt": "2026-06-03T10:05:00Z"
      }
    ],
    "totalPages": 1,
    "totalElements": 1,
    "currentPage": 0,
    "size": 20,
    "first": true,
    "last": true
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

### `GET /api/research/{sessionId}/report` — Get Final Report

Retrieves the synthesized research report for a completed session.

- **Path Parameter**: `sessionId` (UUID, required)

- **Response**: `200 OK` — Raw text of the final report
- **Response**: `400 Bad Request` — Session not yet COMPLETED
- **Response**: `404 Not Found` — No report exists for this session

---

### `POST /api/research/{sessionId}/followup` — Submit Follow-Up Question

Submits a follow-up question about the research findings of a completed session. The LLM answers based on the previous research report and the new question.

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

Establishes a Server-Sent Events connection for real-time progress updates. The client receives typed events as the research progresses through its phases.

- **Path Parameter**: `sessionId` (UUID, required)
- **Produces**: `text/event-stream`
- **Timeout**: 10 minutes (`600000ms`) — the SseEmitter will close after this period of inactivity

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

Sent when a new research step begins. The payload is a description string.

- **Payload**: `"Researching: Sub-topic A"` — Indicates the start of sub-topic research

### `STEP_COMPLETE`

Sent when a step finishes processing. The payload contains metadata about the completed step.

- **Payload**: `{"step": "SUBTOPIC", "index": 2, "status": "COMPLETED"}` — Step completion details

### `CONTENT`

Sent during streaming of sub-topic findings (raw content chunks).

- **Payload**: `"According to recent studies..."` — Content chunk from the LLM response

### `REPORT_START`

Sent when the final report synthesis begins. No payload is included.

- **Payload**: None

### `REPORT_CHUNK`

Sent during streaming of the final research report (raw text chunks).

- **Payload**: `"## Executive Summary\n\n..."` — Report content chunk

### `REPORT_DONE`

Sent when the final report is complete. The payload contains the full report text.

- **Payload**: Complete synthesized research report as markdown text

### `ERROR`

Sent if an error occurs during processing.

- **Payload**: Error message string

---

## Data Models

See [models.md](./MODELS.md) for detailed model definitions with all fields, types, and constraints.
