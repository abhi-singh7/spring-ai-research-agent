package com.researchagent.repository;

import com.researchagent.model.entity.WebPageCacheEntry;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * Repository for the web page content cache (collection {@code web_page_cache}).
 */
public interface WebPageCacheRepository extends MongoRepository<WebPageCacheEntry, String> {

    /** Point lookup by normalized URL — the only read path the cache service uses. */
    Optional<WebPageCacheEntry> findByUrl(String url);
}
