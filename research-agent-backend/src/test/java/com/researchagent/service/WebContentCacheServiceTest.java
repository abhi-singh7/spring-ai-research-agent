package com.researchagent.service;

import com.researchagent.model.entity.WebPageCacheEntry;
import com.researchagent.repository.WebPageCacheRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the web content cache seam: URL normalization (the cache key), find/store
 * behavior, TTL expiry, failure tolerance, and the disabled mode.
 */
class WebContentCacheServiceTest {

    private final WebPageCacheRepository repo = Mockito.mock(WebPageCacheRepository.class);
    private final WebContentCacheService service = new WebContentCacheService(repo, true, Duration.ofDays(7));

    @Test
    void normalizeUrl_lowercasesSchemeAndHost() {
        assertThat(service.normalizeUrl("HTTPS://EXAMPLE.COM/Path")).isEqualTo("https://example.com/Path");
    }

    @Test
    void normalizeUrl_dropsFragment() {
        assertThat(service.normalizeUrl("https://a.example/page#section-2"))
                .isEqualTo("https://a.example/page");
    }

    @Test
    void normalizeUrl_stripsTrailingSlashButKeepsRoot() {
        assertThat(service.normalizeUrl("https://a.example/dir/")).isEqualTo("https://a.example/dir");
        assertThat(service.normalizeUrl("https://a.example/")).isEqualTo("https://a.example/");
    }

    @Test
    void normalizeUrl_dropsTrackingParamsKeepsOthers() {
        assertThat(service.normalizeUrl("https://a.example/p?utm_source=x&fbclid=abc&id=42&gclid=g&igsh=h"))
                .isEqualTo("https://a.example/p?id=42");
    }

    @Test
    void normalizeUrl_keepsMeaningfulQueryParamsDistinct() {
        assertThat(service.normalizeUrl("https://a.example/page?lang=fr"))
                .isNotEqualTo(service.normalizeUrl("https://a.example/page"));
    }

    @Test
    void normalizeUrl_unparseableFallsBackToTrimmedRaw() {
        assertThat(service.normalizeUrl("  not a url  ")).isEqualTo("not a url");
    }

    @Test
    void find_returnsContentForFreshEntry() {
        when(repo.findByUrl("https://a.example/p")).thenReturn(Optional.of(entry("body", LocalDateTime.now())));
        assertThat(service.find("https://a.example/p#frag")).contains("body");
    }

    @Test
    void find_rejectsEntryPastTtl() {
        when(repo.findByUrl("https://a.example/p"))
                .thenReturn(Optional.of(entry("body", LocalDateTime.now().minusDays(8))));
        assertThat(service.find("https://a.example/p")).isEmpty();
    }

    @Test
    void find_emptyContentIsAMiss() {
        when(repo.findByUrl("https://a.example/p")).thenReturn(Optional.of(entry("   ", LocalDateTime.now())));
        assertThat(service.find("https://a.example/p")).isEmpty();
    }

    @Test
    void find_swallowsRepositoryFailure() {
        when(repo.findByUrl(anyString())).thenThrow(new RuntimeException("mongo down"));
        assertThat(service.find("https://a.example/p")).isEmpty();
    }

    @Test
    void store_createsNewEntryWithFetchedAt() {
        when(repo.findByUrl("https://a.example/p")).thenReturn(Optional.empty());
        service.store("https://a.example/p#frag", "content", "jsoup");

        org.mockito.ArgumentCaptor<WebPageCacheEntry> captor = org.mockito.ArgumentCaptor.forClass(WebPageCacheEntry.class);
        verify(repo).save(captor.capture());
        WebPageCacheEntry saved = captor.getValue();
        assertThat(saved.getUrl()).isEqualTo("https://a.example/p");
        assertThat(saved.getContent()).isEqualTo("content");
        assertThat(saved.getSource()).isEqualTo("jsoup");
        assertThat(saved.getFetchedAt()).isAfter(LocalDateTime.now().minusSeconds(5));
    }

    @Test
    void store_updatesExistingEntry() {
        WebPageCacheEntry existing = entry("old", LocalDateTime.now().minusDays(2));
        when(repo.findByUrl("https://a.example/p")).thenReturn(Optional.of(existing));
        service.store("https://a.example/p", "new", "firecrawl");

        verify(repo).save(Mockito.argThat(e -> e.getContent().equals("new")
                && e.getSource().equals("firecrawl")));
    }

    @Test
    void store_ignoresBlankContent() {
        service.store("https://a.example/p", "   ", "jsoup");
        verify(repo, never()).save(Mockito.any(WebPageCacheEntry.class));
    }

    @Test
    void store_swallowsRepositoryFailure() {
        when(repo.findByUrl(anyString())).thenThrow(new RuntimeException("mongo down"));
        service.store("https://a.example/p", "content", "jsoup"); // must not throw
        verify(repo, never()).save(Mockito.any(WebPageCacheEntry.class));
    }

    @Test
    void disabledMode_noOpsWithoutTouchingRepository() {
        WebContentCacheService off = new WebContentCacheService(repo, false, Duration.ofDays(7));
        assertThat(off.find("https://a.example/p")).isEmpty();
        off.store("https://a.example/p", "content", "jsoup");
        verify(repo, never()).findByUrl(anyString());
        verify(repo, never()).save(Mockito.any(WebPageCacheEntry.class));
    }

    private static WebPageCacheEntry entry(String content, LocalDateTime fetchedAt) {
        return WebPageCacheEntry.builder()
                .url("https://a.example/p")
                .content(content)
                .source("jsoup")
                .fetchedAt(fetchedAt)
                .build();
    }
}
