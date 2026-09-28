package com.researchagent.repository;

import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.enums.ResearchStatus;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Default implementation of {@link ResearchSessionRepositoryCustom} (Spring Data repository fragment).
 */
public class ResearchSessionRepositoryCustomImpl implements ResearchSessionRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    public ResearchSessionRepositoryCustomImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public int markCancelledIfProcessing(UUID id, LocalDateTime completedAt, String finalReport) {
        // Explicit string comparison: enum literals in query/update documents are dialect-fragile elsewhere,
        // and the stored value is the enum name.
        Query query = Query.query(
                Criteria.where("id").is(id).and("status").is(ResearchStatus.PROCESSING.name()));
        Update update = new Update()
                .set("status", ResearchStatus.CANCELLED.name())
                .set("completedAt", completedAt)
                .set("finalReport", finalReport);
        return (int) mongoTemplate.updateFirst(query, update, ResearchSession.class).getMatchedCount();
    }
}
