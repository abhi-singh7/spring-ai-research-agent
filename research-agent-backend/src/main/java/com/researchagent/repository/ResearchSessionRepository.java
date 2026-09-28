package com.researchagent.repository;

import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.enums.ResearchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ResearchSessionRepository extends MongoRepository<ResearchSession, UUID>, ResearchSessionRepositoryCustom {

    /**
     * Find session by id. Steps are embedded in the document, so a plain find already returns
     * the full aggregate — this alias is kept for call-site and test compatibility with the old
     * JPA fetch-join method of the same name.
     */
    default ResearchSession findByIdWithSteps(UUID id) {
        return findById(id).orElse(null);
    }

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
}
