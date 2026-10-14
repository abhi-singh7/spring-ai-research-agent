package com.researchagent.repository;

import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.enums.ResearchStatus;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;
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

    @Override
    public int markResumableIfTerminal(UUID id, LocalDateTime updatedAt) {
        Query query = Query.query(
                Criteria.where("id").is(id)
                        .and("status").in(ResearchStatus.FAILED.name(), ResearchStatus.CANCELLED.name()));
        Update update = new Update()
                .set("status", ResearchStatus.PROCESSING.name())
                .set("updatedAt", updatedAt)
                .unset("finalReport")
                .unset("completedAt");
        return (int) mongoTemplate.updateFirst(query, update, ResearchSession.class).getMatchedCount();
    }

    /**
     * Owner-scoped history query. The {@code owner} criteria uses {@code $in: [username, null]}: MongoDB
     * matches a MISSING field with a null equality term, so legacy sessions written before the ownership
     * change stay reachable without any migration. The page window is pushed into the query (skip/limit)
     * so only one page of documents is transferred; the sort is part of the query so paging is stable.
     */
    @Override
    public Page<ResearchSession> findOwnedByOrderByCreatedAtDesc(String owner, Pageable pageable) {
        Query filter = Query.query(Criteria.where("owner").in(owner, null));
        long total = mongoTemplate.count(filter, ResearchSession.class);
        List<ResearchSession> slice = mongoTemplate.find(paged(filter, pageable), ResearchSession.class);
        return new PageImpl<>(slice, pageable, (int) total);
    }

    @Override
    public Page<ResearchSession> findByTopicContainingIgnoreCaseAndOwner(String topicQuery, String owner, Pageable pageable) {
        // (?i) + \Q…\E = case-insensitive literal match (ICU regex), so metacharacters in the query are data.
        Query filter = Query.query(Criteria.where("topic").regex("(?i)\\Q" + literalRegex(topicQuery) + "\\E")
                .and("owner").in(owner, null));
        long total = mongoTemplate.count(filter, ResearchSession.class);
        List<ResearchSession> slice = mongoTemplate.find(paged(filter, pageable), ResearchSession.class);
        return new PageImpl<>(slice, pageable, (int) total);
    }

    /** Push the page window into the query so only one page of documents crosses the wire. */
    private Query paged(Query filter, Pageable pageable) {
        Query sorted = filter.with(Sort.by(Sort.Direction.DESC, "createdAt"));
        return pageable.isUnpaged() ? sorted
                : sorted.skip(pageable.getOffset()).limit(pageable.getPageSize());
    }

    @Override
    public long countOwnedBy(String owner) {
        return mongoTemplate.count(Query.query(Criteria.where("owner").is(owner)), ResearchSession.class);
    }

    /** Neutralise the only sequence that would end the \Q…\E literal quote early. */
    private String literalRegex(String value) {
        return value == null ? "" : value.replace("\\E", "");
    }
}
