package com.researchagent.repository;

import com.researchagent.model.entity.LLMLogs;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for the per-LLM-call log documents written by {@code LoggingAdvisor}.
 */
public interface LlmLogRepository extends MongoRepository<LLMLogs, String> {

    /** All LLM calls made during one research session, in call order. */
    List<LLMLogs> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    long countBySessionId(UUID sessionId);

    void deleteBySessionId(UUID sessionId);
}
