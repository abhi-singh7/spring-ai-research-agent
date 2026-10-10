package com.researchagent.service;

import com.researchagent.model.entity.WebPageCacheEntry;
import com.researchagent.repository.WebPageCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-MongoDB verification of the web page content cache (local test database, same pattern as
 * {@link OrchestratorPersistenceIntegrationTest}): save/lookup round-trip, upsert-overwrite on the
 * same URL, and — critically — that Spring Data auto-created the TTL index on {@code fetchedAt}
 * (expireAfterSeconds = 7d) so entries self-expire without any application-side purge job.
 */
@SpringBootTest
class WebPageCacheIntegrationTest {

    @Autowired
    private WebPageCacheRepository repo;

    @Autowired
    private WebContentCacheService cache;

    @Autowired
    private MongoTemplate mongoTemplate;

    /** The test database persists across runs on the local MongoDB — start each test from a clean slate. */
    @BeforeEach
    void cleanDatabase() {
        repo.deleteAll();
    }

    @Test
    void storeThenFind_roundTripsThroughMongo() {
        cache.store("https://example.com/article", "Round-trip content.", "jsoup");

        Optional<String> found = cache.find("https://example.com/article#section?utm_source=test");
        assertThat(found).contains("Round-trip content.");

        WebPageCacheEntry entry = repo.findByUrl("https://example.com/article").orElseThrow();
        assertThat(entry.getSource()).isEqualTo("jsoup");
        assertThat(entry.getFetchedAt()).isNotNull();
    }

    @Test
    void storingSameUrlAgain_overwritesInPlace() {
        cache.store("https://example.com/one", "first version", "jsoup");
        cache.store("https://example.com/one/", "second version", "firecrawl"); // trailing slash → same key

        assertThat(repo.count()).isEqualTo(1);
        WebPageCacheEntry entry = repo.findByUrl("https://example.com/one").orElseThrow();
        assertThat(entry.getContent()).isEqualTo("second version");
        assertThat(entry.getSource()).isEqualTo("firecrawl");
    }

    @Test
    void ttlIndexOnFetchedAt_isAutoCreatedWithSevenDayExpiry() {
        // Touch the collection so indexes are ensured, then inspect what Mongo actually built.
        cache.store("https://example.com/ttl", "content", "jsoup");

        var indexes = mongoTemplate.getCollection("web_page_cache").listIndexes()
                .into(new java.util.ArrayList<org.bson.Document>());
        org.bson.Document ttlIndex = indexes.stream()
                .filter(i -> i.get("key") instanceof org.bson.Document key
                        && key.containsKey("fetchedAt"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no TTL index on fetchedAt: " + indexes));

        // expireAfterSeconds of exactly one week (7 * 24 * 60 * 60) — the Mongo TTL monitor
        // deletes entries ~1 week after fetch with no application-side purge job.
        assertThat(ttlIndex.getInteger("expireAfterSeconds")).isEqualTo(7 * 24 * 60 * 60);
    }
}
