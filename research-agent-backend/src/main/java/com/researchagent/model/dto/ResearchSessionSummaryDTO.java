package com.researchagent.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of the history list. Deliberately NOT {@code ResearchSession}: the entity carries the full
 * {@code finalReport} (and prompt), which used to be serialized into every row of every history page —
 * tens of kilobytes per session that the list view never renders. The detail endpoint
 * ({@code GET /api/research/history/{id}}) still returns {@link ResearchSessionDetailDTO} with the report
 * and steps; the list only says whether a report exists ({@code reportAvailable}) and how many steps ran.
 *
 * Field names mirror the entity so the Angular {@code ResearchHistoryItem} model keeps working unchanged.
 */
@Data
public class ResearchSessionSummaryDTO {

    private UUID id;
    private String topic;
    private String status;
    private String owner;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

    /** True when {@code finalReport} is non-empty — the list can offer a "view report" action without shipping the text. */
    private boolean reportAvailable;

    /** How many steps ran, so the list can show progress without shipping the step bodies. */
    private int stepCount;
}
