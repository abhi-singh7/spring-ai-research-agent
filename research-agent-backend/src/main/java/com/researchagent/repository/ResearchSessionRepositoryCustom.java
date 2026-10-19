package com.researchagent.repository;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Custom fragment for operations derived queries cannot express. Spring Data wires the
 * conventionally-named {@link ResearchSessionRepositoryCustomImpl} into the generated
 * {@link ResearchSessionRepository}.
 */
public interface ResearchSessionRepositoryCustom {

    /**
     * Atomically transition a session to CANCELLED only if it is still PROCESSING. The conditional
     * updateFirst() matches at most one document, so the "no clobber" rule holds at the database
     * level: documents already COMPLETED/FAILED/CANCELLED are left untouched.
     *
     * @return matched document count — 0 means another writer (e.g. a just-finished run) reached a
     *         terminal state first and nothing was changed
     */
    int markCancelledIfProcessing(UUID id, LocalDateTime completedAt, String finalReport);

    /**
     * Atomically claim a session for resume: transition it back to PROCESSING only if it is currently
     * FAILED or CANCELLED, clearing the failure report and terminal timestamp. The conditional
     * updateFirst() matches at most one document, so concurrent resume requests cannot both win —
     * the loser sees 0 matched documents.
     *
     * @return matched document count — 0 means the session is not in a resumable state (e.g. already
     *         PROCESSING/COMPLETED, or a concurrent resume won the race) and nothing was changed
     */
    int markResumableIfTerminal(UUID id, LocalDateTime updatedAt);

    /**
     * Owner-scoped history page, newest first. {@code owner} matches sessions created by that user PLUS
     * legacy sessions that have no {@code owner} field at all (MongoDB matches a missing field with a
     * null equality term), so pre-ownership history stays visible after the upgrade. Filtering happens in
     * the query, never after pagination, so page size and {@code totalElements} describe the filtered set.
     */
    org.springframework.data.domain.Page<com.researchagent.model.entity.ResearchSession> findOwnedByOrderByCreatedAtDesc(
            String owner, org.springframework.data.domain.Pageable pageable);

    /**
     * Owner-scoped topic search (case-insensitive), newest first — same legacy-null rule as
     * {@link findOwnedByOrderByCreatedAtDesc}.
     */
    org.springframework.data.domain.Page<com.researchagent.model.entity.ResearchSession> findByTopicContainingIgnoreCaseAndOwner(
            String query, String owner, org.springframework.data.domain.Pageable pageable);

    /** Sessions owned by the given user (legacy unowned sessions excluded) — resource-limit diagnostics. */
    long countOwnedBy(String owner);
}
