# Research Agent Backend — Data Models

## Domain Entities (Database)

### `research_session` Table

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PRIMARY KEY, DEFAULT gen_random_uuid() | Unique session identifier |
| `topic` | VARCHAR(1024) | NOT NULL | User's original research query |
| `status` | VARCHAR(32) | NOT NULL, DEFAULT 'PENDING' | Current session state |
| `prompt` | TEXT | (nullable) | System prompt used for this session |
| `created_at` | TIMESTAMP WITH TIME ZONE | DEFAULT NOW() | Session creation timestamp |
| `updated_at` | TIMESTAMP WITH TIME ZONE | DEFAULT NOW() | Last update timestamp |
| `completed_at` | TIMESTAMP WITH TIME ZONE | (nullable) | Completion/failure timestamp |
| `final_report` | TEXT | (nullable) | Synthesized research report content |

**Indexes:**
- `idx_research_session_status` on `(status)` — for filtering by status in history queries
- `idx_research_session_created` on `(created_at DESC)` — for ordering history results

### `research_step` Table

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | UUID | PRIMARY KEY, DEFAULT gen_random_uuid() | Unique step identifier |
| `session_id` | UUID | NOT NULL, FK → research_session(id) ON DELETE CASCADE | Parent session reference |
| `order_index` | INTEGER | NOT NULL — unique within a session (with session_id) | Step ordinal position |
| `type` | VARCHAR(32) | NOT NULL | Type of step being executed |
| `content` | TEXT | (nullable) | Step output content (may be null if step failed before producing content) |
| `status` | VARCHAR(32) | NOT NULL, DEFAULT 'PENDING' | Current step state |
| `created_at` | TIMESTAMP WITH TIME ZONE | DEFAULT NOW() | Step creation timestamp |

**Indexes:**
- Unique constraint on `(session_id, order_index)` — ensures step ordering within a session
- `idx_research_step_session` on `(session_id)` — for fetching all steps of a session

---

## Enums

### ResearchStatus (Entity Enum)

| Value | Meaning | Transitions From |
|-------|---------|------------------|
| `PENDING` | Session created but not yet started | None — transient state, immediately transitions to PROCESSING |
| `PROCESSING` | Research is actively running | PENDING |
| `COMPLETED` | Research finished successfully | PROCESSING (only) |
| `FAILED` | Research failed due to an error | PROCESSING (on exception) |
| `CANCELLED` | Research cancelled by user or cleanup scheduler | PROCESSING (via DELETE endpoint or `AbandonedSessionCleanupService`) |

### StepType (Entity Enum)

| Value | Meaning | Phase |
|-------|---------|-------|
| `BREAKDOWN` | Topic breakdown into sub-topics | Phase 1 |
| `SUBTOPIC` | Individual sub-topic research | Phase 2 |
| `SEARCH` | Web search via MCP/local tools | Phase 2 (within SUBTOPIC) |
| `READ` | URL content extraction via MCP/local tools | Phase 2 (within SUBTOPIC) |
| `SYNTHESIS` | Finding synthesis | Phase 3 |
| `FINAL_REPORT` | Final report generation | Phase 3 |

---

## DTOs (Data Transfer Objects)

### ResearchRequest — Incoming Request to Start Research

```java
public class ResearchRequest {
    @NotBlank String topic;                    // The research topic to investigate
    @Min(1) Integer maxIterations = 3;         // Max sub-topic iterations to process
    @Min(1) Integer subTopicCount = 5;         // Number of sub-topics from LLM breakdown
    Boolean streamingEnabled = true;            // Enable SSE streaming for real-time updates
}
```

### ResearchResponse — Response After Starting Research

```java
public class ResearchResponse {
    String id;                                   // Session UUID
    String topic;                                // Original research topic
    String status;                               // Current session status (ResearchStatus enum name)
    LocalDateTime createdAt;                     // Creation timestamp
    LocalDateTime completedAt;                   // Completion/failure timestamp (nullable)
    String finalReport;                          // Synthesized report content (nullable)
    List<StepDTO> steps;                         // Current step list for this session
}
```

### ResearchSessionDetailDTO — Full Session Detail with Steps and Content

Used by `GET /api/research/history/{sessionId}` to return the complete session view including prompt, final report, and all step content.

```java
public class ResearchSessionDetailDTO {
    String id;                                   // Session UUID
    String topic;                                // Original research topic
    String status;                               // Current session status
    String prompt;                               // System prompt used for this session
    String finalReport;                          // Synthesized report content (nullable)
    LocalDateTime createdAt;                     // Creation timestamp
    LocalDateTime updatedAt;                     // Last update timestamp
    LocalDateTime completedAt;                   // Completion/failure timestamp (nullable)
    List<StepDTO> steps;                         // Full step list with content included
}
```

### StepDTO — Individual Step Representation

```java
public class StepDTO {
    Integer orderIndex;                          // Step ordinal position within session
    String type;                                 // StepType enum name (BREAKDOWN/SUBTOPIC/SEARCH/READ/SYNTHESIS/FINAL_REPORT)
    String status;                               // Step state: PENDING/RUNNING/COMPLETED/FAILED
    String content;                              // Step output content (nullable if step failed early)
}
```

### StreamUpdate — SSE Event Payload

```java
public class StreamUpdate {
    EventType type;                              // Type of event being sent
    UUID sessionId;                              // Session this event belongs to
    Object payload;                              // Event-specific data (String for most, Map for STEP_COMPLETE)
}
```

#### EventType (Nested Enum)

| Value | Meaning | Payload Type |
|-------|---------|-------------|
| `PROGRESS` | A new step has started / sub-topic transitioned | String — description text |
| `CONTENT` | Streaming content from sub-topic research | String — raw text chunk |
| `REPORT_START` | Final report synthesis has begun | None (null) |
| `REPORT_CHUNK` | Raw text chunk during final report streaming | String — report chunk |
| `REPORT_DONE` | Final report generation complete | ReportDTO or String |
| `STEP_COMPLETE` | A step has completed processing | Map<String, Object> — flexible matching payload (string/number/object) |
| `ERROR` | An error occurred during processing | String — error message |

### ReportDTO — Report Payload for REPORT_DONE Event

```java
public class ReportDTO {
    UUID sessionId;                              // Session that generated the report
    String topic;                                // Original research topic
    String reportContent;                        // Complete synthesized report text
    LocalDateTime createdAt;                     // Report generation timestamp
}
```

### FollowUpRequest — Incoming Follow-Up Question Request

```java
public class FollowUpRequest {
    @NotBlank String question;                   // The follow-up question to answer
}
```

---

## LLM Sub-Topic Model (Internal, Used During Orchestration)

This model is used internally by `ResearchOrchestratorService` for JSON parsing of the LLM's topic breakdown response. It is not exposed as a DTO.

```java
public static class SubTopic {
    String id;            // Unique identifier within this session
    String title;         // Short title of the sub-topic
    String description;   // Brief description of what to research in this sub-topic
}
```

When JSON parsing fails, a fallback creates a single `SubTopic` with:
- `id`: "1"
- `title`: The original request topic (from `request.getTopic()`)
- `description`: The raw unparsed LLM response string (fragile — not validated)

---

## Internal Step Name Mapping

The frontend maps backend step types to human-readable names via `getStepName()` in the ResearchService:

| Backend Type | Frontend Display Name |
|-------------|----------------------|
| BREAKDOWN | Breakdown |
| SUBTOPIC | Sub-topic {n} (numbered dynamically) |
| SEARCH | Search |
| READ | Read URL |
| SYNTHESIS | Synthesis |
| FINAL_REPORT | Generating Report |
