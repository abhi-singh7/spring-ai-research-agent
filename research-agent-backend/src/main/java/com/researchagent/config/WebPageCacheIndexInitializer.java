package com.researchagent.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Explicitly ensures the {@code web_page_cache} collection indexes at startup.
 *
 * <p>Spring Data MongoDB 5.x (used by Spring Boot 4) defaults
 * {@code spring.data.mongodb.auto-index-creation} to <b>false</b> — the opposite of pre-5.0 behavior —
 * so annotation-driven index creation does NOT happen automatically in this project. The cache's
 * two indexes are therefore created here, deterministically and scoped to this feature:
 *
 * <ul>
 *   <li>{@code url} — unique (one entry per normalized URL; upserts on re-fetch),</li>
 *   <li>{@code fetchedAt} — TTL with {@code expireAfterSeconds = app.cache.ttl}, letting the Mongo
 *       server self-expire entries without any application-side purge job.</li>
 * </ul>
 */
@Slf4j
@Component
public class WebPageCacheIndexInitializer {

    private final MongoOperations mongoOperations;
    private final java.time.Duration ttl;

    public WebPageCacheIndexInitializer(MongoOperations mongoOperations,
                                        @org.springframework.beans.factory.annotation.Value("${app.cache.ttl:PT168H}") java.time.Duration ttl) {
        this.mongoOperations = mongoOperations;
        this.ttl = ttl;
    }

    @PostConstruct
    void ensureIndexes() {
        // Must stay in sync with @Document(collection = "web_page_cache") on WebPageCacheEntry.
        String collection = "web_page_cache";
        mongoOperations.indexOps(collection).ensureIndex(
                new Index("url", Sort.Direction.ASC).unique().named("web_page_cache_url_1"));
        mongoOperations.indexOps(collection).ensureIndex(
                new Index("fetchedAt", Sort.Direction.ASC).expire(ttl).named("web_page_cache_fetched_at_ttl"));
        log.info("Ensured web_page_cache indexes: unique(url), TTL(fetchedAt, {}s)", ttl.toSeconds());
    }
}
