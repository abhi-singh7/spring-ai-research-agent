package com.researchagent.repository;

import com.researchagent.model.entity.ResearchSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

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
}
