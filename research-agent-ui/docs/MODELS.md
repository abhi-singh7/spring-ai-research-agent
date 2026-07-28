# Research Agent Frontend — Models

## Overview

All TypeScript interfaces and types are defined in a single file: `src/app/core/models/research.model.ts`. There are no separate model files per domain — the frontend uses minimal type definitions (interfaces, not DTOs) that mirror the backend structure.

---

## Status Types

### ResearchStatus

```typescript
type ResearchStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
```

Matches the backend `ResearchStatus` enum values exactly. Used for status chips and conditional rendering throughout components.

---

## Session Models (Backend Response Types)

### ResearchSession — Full Session State

Returned from all REST endpoints (`/api/research/*`). Represents the complete session state including optional fields that are populated as research progresses.

```typescript
export interface ResearchSession {
  id: string;              // Session UUID
  topic: string;           // Original research query
  status: ResearchStatus;  // Current state
  createdAt?: string;      // ISO timestamp — only present after first update
  updatedAt?: string;      // ISO timestamp — updated on each SSE event
  completedAt?: string;    // ISO timestamp — only set when COMPLETED or FAILED
  finalReport?: string;    // Synthesized report content — only present for COMPLETED sessions
  streamUrl?: string;      // Optional custom SSE URL — not used in current implementation
  steps?: HistoryDetailStep[];  // Steps from history detail endpoint (not always present)
}
```

### ResearchStartRequest — Request to Start Research

Sent to `POST /api/research`. All fields except `topic` are optional with sensible defaults.

```typescript
export interface ResearchStartRequest {
  topic: string;                // Required — research topic
  streamingEnabled?: boolean;   // Default: true (SSE streaming enabled)
  maxIterations?: number;       // Default: 3 (max sub-topic iterations)
  subTopicCount?: number;       // Default: 5 (number of sub-topics from LLM breakdown)
}
```

### ResearchHistoryItem — History List Item

Returned in paginated history responses. Contains only the fields relevant to the list view — excludes `finalReport`, `steps`, etc. for efficiency.

```typescript
export interface ResearchHistoryItem {
  id: string;           // Session UUID
  topic: string;        // Research query
  status: ResearchStatus; // Final status (COMPLETED, FAILED, etc.)
  createdAt: string;    // Required — always present in history results
  updatedAt?: string;   // Optional — may not be set for older sessions
}
```

---

## Step Models

### HistoryDetailStep — From Backend Detail Endpoint

Returned from `GET /api/research/history/{sessionId}`. Includes the step content (finding text).

```typescript
export interface HistoryDetailStep {
  orderIndex: number;
  type: StepType;                       // BREAKDOWN | SEARCH | READ | SYNTHESIS | SUBTOPIC | FINAL_REPORT
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED';
  content?: string;                     // Finding text or parsed JSON (BREAKDOWN steps)
}
```

### ResearchStep — Frontend Internal Step Representation

Represents a single step in the research pipeline. Used internally by components and signals — not directly returned by any REST endpoint. The `steps` list from the backend uses `HistoryDetailStep`, but the frontend transforms these into `ResearchStep[]` format via `getStepName()`.

```typescript
export interface ResearchStep {
  stepNumber: number;           // Step ordinal — 1-based index within session
  name: string;                 // Human-readable step name (e.g., "Breakdown", "Sub-topic 2")
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED';
                                // Note: frontend uses 'IN_PROGRESS' not the backend's 'RUNNING'
  description?: string;         // Optional — step detail text from SSE PROGRESS events
  duration?: number;            // Optional — step execution time in milliseconds
}
```

### StepType (Frontend Enum)

```typescript
export type StepType = 'BREAKDOWN' | 'SEARCH' | 'READ' | 'SYNTHESIS' | 'SUBTOPIC' | 'FINAL_REPORT';
```

Matches the backend `StepType` entity enum values. Used for step categorization and rendering in the step-list component.

### Backend → Frontend Step Name Mapping

The frontend's `ResearchService.getStepName()` converts backend types to display names:

| Backend Type | Display Name | Notes |
|-------------|-------------|-------|
| BREAKDOWN | Breakdown | Phase 1 topic breakdown |
| SUBTOPIC | Sub-topic {n} | Numbered dynamically per session |
| SEARCH | Search | Web search via MCP/local tools |
| READ | Read URL | Content extraction from specific URLs |
| SYNTHESIS | Synthesis | Finding synthesis step |
| FINAL_REPORT | Generating Report | Phase 3 report generation |

---

## Pagination Model

### PaginatedResult<T>

Generic wrapper for paginated responses from the history endpoints. Mirrors the Spring Data Page structure returned by the backend.

```typescript
export interface PaginatedResult<T> {
  content: T[];            // Items on this page (array of items)
  totalElements: number;   // Total items across all pages
  totalPages: number;      // Total number of pages available
  currentPage: number;     // Current page index (0-based from backend)
  size: number;            // Items per page (page size)
}
```

---

## Follow-up Models

### FollowUpRequest — Request Body for Follow-up Question

```typescript
export interface FollowUpRequest {
  question: string;        // The follow-up question text
}
```

### FollowUpResponse — Response from Follow-up Endpoint

Returned from `POST /api/research/{sessionId}/followup`. Contains the LLM-generated answer. Note: the backend returns raw text (string), not an object with sessionId/answer/timestamp fields — the frontend interface includes these for completeness but the actual response is a plain string.

```typescript
export interface FollowUpResponse {
  sessionId: string;       // Session that generated this answer
  answer: string;          // The LLM's response text
  timestamp: string;       // ISO timestamp of when the answer was generated
}
```

---

## SSE Event Types (Union Types)

### SseEventType

Discriminated union type used to narrow event types in switch statements.

```typescript
export type SseEventType = 'PROGRESS' | 'CONTENT' | 'REPORT_CHUNK' | 
                           'REPORT_DONE' | 'STEP_COMPLETE' | 'ERROR' | 'REPORT_START';
```

### Base Interface — SseEvent

Common fields shared by all SSE events: `type` (discriminator) and optional `sessionId`.

```typescript
export interface SseEvent {
  type: SseEventType;
  sessionId?: string;      // Optional — may not be present on all event types
}
```

### Specific Event Interfaces

Each extends `SseEvent` with a literal `type` value and event-specific payload. The discriminated union pattern allows TypeScript to narrow the type within switch statements:

```typescript
// Progress update during research (start/transition of sub-topic)
export interface ProgressSseEvent extends SseEvent {
  type: 'PROGRESS';
  payload: string;         // Step description text (e.g., "Researching: Sub-topic A" or "Completed research on: X")
}

// Streaming content from sub-topic research phase
export interface ContentSseEvent extends SseEvent {
  type: 'CONTENT';
  payload: string;         // Raw text chunk from LLM response
}

// Report synthesis has started — no additional data needed
export interface ReportStartSseEvent extends SseEvent {
  type: 'REPORT_START';
  sessionId?: string;      // Optional session reference for the report being generated
}

// Individual chunk of final report during streaming
export interface ReportChunkSseEvent extends SseEvent {
  type: 'REPORT_CHUNK';
  payload: string;         // Report content chunk to accumulate into _reportContentSignal
}

// Final report generation complete — full text provided as payload
export interface ReportDoneSseEvent extends SseEvent {
  type: 'REPORT_DONE';
  payload: string;         // Complete final report text (markdown)
}

// Step completion metadata — flexible matching in handleStepComplete()
export interface StepCompleteSseEvent extends SseEvent {
  type: 'STEP_COMPLETE';
  payload: Record<string, unknown>;  // May be string (step name), number (index/stepNumber), or object with stepName/name/index/stepNumber keys
}

// Error notification during processing
export interface ErrorSseEvent extends SseEvent {
  type: 'ERROR';
  payload: string;         // Error message text
}
```
