package com.researchagent.repository;

import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.enums.ResearchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ResearchSessionRepository extends JpaRepository<ResearchSession, UUID> {

    /**
     * Find session with its steps eagerly fetched.
     */
    @Query("SELECT s FROM ResearchSession s LEFT JOIN FETCH s.steps WHERE s.id = :id")
    ResearchSession findByIdWithSteps(UUID id);

    /**
     * Find all sessions ordered by creation date descending, paginated.
     */
    Page<ResearchSession> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Search sessions by topic (case-insensitive), paginated.
     */
    Page<ResearchSession> findByTopicContainingIgnoreCase(String query, Pageable pageable);

    /**
     * Find abandoned PROCESSING sessions older than the given cutoff timestamp.
     */
    List<ResearchSession> findAllByStatusAndCreatedAtBefore(ResearchStatus status, LocalDateTime cutoff);

    /**
     * Atomically transition a session to CANCELLED only if it is still PROCESSING. Native SQL keeps
     * the string comparison explicit (enum literals in JPQL UPDATE-WHERE are dialect-fragile) and
     * makes the "no clobber" rule atomic at the DB level: rows already COMPLETED/FAILED/CANCELLED
     * are left untouched.
     *
     * @return affected row count — 0 means another writer (e.g. a just-finished run) reached a
     *         terminal state first and nothing was changed
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE research_session SET status = 'CANCELLED', completed_at = :completedAt, " +
            "final_report = :finalReport WHERE id = :id AND status = 'PROCESSING'", nativeQuery = true)
    int markCancelledIfProcessing(@Param("id") UUID id,
                                  @Param("completedAt") LocalDateTime completedAt,
                                  @Param("finalReport") String finalReport);
}
