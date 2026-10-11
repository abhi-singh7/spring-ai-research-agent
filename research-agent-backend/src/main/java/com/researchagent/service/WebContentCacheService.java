package com.researchagent.service;

import com.researchagent.model.entity.WebPageCacheEntry;
import com.researchagent.repository.WebPageCacheRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The single seam both reader tools consult for cached page content.
 *
 * <p>Entries live in the {@code web_page_cache} collection keyed by NORMALIZED URL and expire via a
 * MongoDB TTL index on {@code fetchedAt} (P7D). Every operation is failure-tolerant: a Mongo error
 * degrades to "no cache" (direct fetch / plain results) — caching must never break the research
 * pipeline. With {@code app.cache.enabled=false} both methods are no-ops and the tools behave
 * exactly as before the cache existed.</p>
 */
@Service
@Slf4j
public class WebContentCacheService {

    /** Tracking query params that never change page content — dropped from the cache key. */
    private static final Set<String> TRACKING_PARAMS = Set.of("fbclid", "gclid", "igsh");

    private final WebPageCacheRepository repo;
    private final boolean enabled;
    private final Duration ttl;

    public WebContentCacheService(WebPageCacheRepository repo,
                                  @Value("${app.cache.enabled:true}") boolean enabled,
                                  @Value("${app.cache.ttl:PT72H}") Duration ttl) {
        this.repo = repo;
        this.enabled = enabled;
        this.ttl = ttl;
    }

    /**
     * Look up cached content for a raw URL. Returns empty when the cache is disabled, the URL has
     * no entry, the entry is past its TTL, or the lookup itself fails.
     */
    public Optional<String> find(String rawUrl) {
        if (!enabled || rawUrl == null || rawUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            return repo.findByUrl(normalizeUrl(rawUrl))
                    .filter(entry -> entry.getContent() != null && !entry.getContent().isBlank())
                    .filter(entry -> entry.getFetchedAt() != null
                            && entry.getFetchedAt().plus(ttl).isAfter(LocalDateTime.now()))
                    .map(WebPageCacheEntry::getContent);
        } catch (Exception e) {
            log.warn("Web content cache lookup failed for {} — degrading to direct fetch: {}", rawUrl, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Store non-empty content for a raw URL (upsert by normalized URL; last write wins). Blank
     * content is never stored (no negative caching), and store failures are swallowed.
     */
    public void store(String rawUrl, String content, String source) {
        if (!enabled || rawUrl == null || rawUrl.isBlank() || content == null || content.isBlank()) {
            return;
        }
        try {
            String key = normalizeUrl(rawUrl);
            WebPageCacheEntry existing = repo.findByUrl(key).orElse(null);
            LocalDateTime now = LocalDateTime.now();
            if (existing != null) {
                existing.setContent(content);
                existing.setSource(source);
                existing.setFetchedAt(now);
                repo.save(existing);
            } else {
                repo.save(WebPageCacheEntry.builder()
                        .url(key)
                        .content(content)
                        .source(source)
                        .fetchedAt(now)
                        .build());
            }
        } catch (Exception e) {
            log.warn("Web content cache store failed for {} — continuing uncached: {}", rawUrl, e.getMessage());
        }
    }

    /**
     * Normalize a URL into its cache key: lowercase scheme/host, drop fragment, strip trailing path
     * slash, drop tracking query params (utm_*, fbclid, gclid, igsh). All other query params are
     * kept — they can change page content. Unparseable URLs fall back to their trimmed raw form.
     */
    public String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            int port = uri.getPort();
            String authority = host + (port >= 0 ? ":" + port : "");

            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            Set<String> keptParams = new LinkedHashSet<>();
            if (uri.getQuery() != null) {
                for (String pair : uri.getQuery().split("&")) {
                    if (pair.isEmpty()) continue;
                    int eq = pair.indexOf('=');
                    String name = (eq >= 0 ? pair.substring(0, eq) : pair).trim();
                    if (name.startsWith("utm_") || TRACKING_PARAMS.contains(name.toLowerCase())) {
                        continue;
                    }
                    keptParams.add(pair);
                }
            }

            StringBuilder sb = new StringBuilder();
            if (!scheme.isEmpty()) sb.append(scheme).append(":");
            if (!authority.isEmpty()) sb.append("//").append(authority);
            sb.append(path);
            if (!keptParams.isEmpty()) sb.append('?').append(String.join("&", keptParams));
            // fragment intentionally dropped
            return sb.toString();
        } catch (Exception e) {
            return trimmed;
        }
    }
}
