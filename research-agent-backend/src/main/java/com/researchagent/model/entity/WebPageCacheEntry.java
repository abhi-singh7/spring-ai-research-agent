package com.researchagent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * One document per cached web page (collection {@code web_page_cache}).
 *
 * <p>Keyed by the NORMALIZED URL (see {@code WebContentCacheService.normalizeUrl}) so equivalent
 * URLs (fragment, trailing slash, tracking params) share one entry. The unique index makes
 * concurrent stores from parallel research sessions race-safe: last write wins, and content for a
 * given URL is reader-deterministic so overwrites are harmless.</p>
 *
 * <p>The {@code @Indexed} annotations below are documentation only — Spring Data MongoDB 5.x
 * (Boot 4) does NOT auto-create indexes. Both indexes are created explicitly at startup by
 * {@code WebPageCacheIndexInitializer}.</p>
 *
 * <p>Expiry is handled by MongoDB's TTL monitor: the P7D index on {@link #fetchedAt} deletes
 * documents ~1 week after fetch (TTL expires at fieldValue + 7d). The
 * application additionally treats an entry as expired once {@code fetchedAt + ttl} has passed, so a
 * lagging TTL monitor can never serve stale content.</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "web_page_cache")
public class WebPageCacheEntry {

    @Id
    private String id;

    /** Normalized URL — the cache key. */
    @Indexed(unique = true)
    private String url;

    /** Extracted readable text / scraped markdown, already bounded (≤ 15 000 chars) by the readers. */
    @ToString.Exclude
    private String content;

    /** Which reader produced the content: "jsoup" or "firecrawl". */
    private String source;

    /** Fetch time — TTL-indexed; MongoDB deletes the document ~7 days after this instant. */
    @Indexed(expireAfter = "P7D")
    private LocalDateTime fetchedAt;
}
