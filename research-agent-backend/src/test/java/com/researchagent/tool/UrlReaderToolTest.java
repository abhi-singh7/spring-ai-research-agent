package com.researchagent.tool;

import com.researchagent.model.entity.WebPageCacheEntry;
import com.researchagent.repository.WebPageCacheRepository;
import com.researchagent.service.WebContentCacheService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the cache-first behavior of {@link UrlReaderTool}: a non-expired cache hit returns without
 * ANY network I/O (no Jsoup, no Firecrawl), a miss fetches and stores the result, and empty pages
 * are never cached.
 */
class UrlReaderToolTest {

    /** Minimal stub HTTP server: serves one configurable response per context path, counts hits. */
    static class StubServer implements AutoCloseable {
        final String context;
        final AtomicInteger hits = new AtomicInteger();
        volatile int status = 200;
        volatile String body = "";
        private HttpServer server;

        StubServer(String context) { this.context = context; }

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(context, exchange -> {
                hits.incrementAndGet();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } else {
                    exchange.close();
                }
            });
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        @Override public void close() { server.stop(0); }
    }

    private StubServer page;      // serves the HTML page Jsoup fetches
    private StubServer firecrawl; // serves /v2/scrape
    private WebPageCacheRepository repo;
    private WebContentCacheService cache;
    private UrlReaderTool tool;

    private static final String ARTICLE_HTML = """
            <html><head><style>.x{color:red}</style></head>
            <body><nav>menu</nav><article>The full article body text that the reader extracts from the page.</article></body></html>
            """;

    @BeforeEach
    void setUp() throws IOException {
        page = new StubServer("/");
        firecrawl = new StubServer("/v2/scrape");
        page.start();
        firecrawl.start();
        repo = Mockito.mock(WebPageCacheRepository.class);
        cache = new WebContentCacheService(repo, true, Duration.ofDays(7));
        tool = new UrlReaderTool(firecrawl.baseUrl(), cache);
    }

    @AfterEach
    void tearDown() throws IOException {
        page.close();
        firecrawl.close();
    }

    @Test
    void cacheHit_returnsContentWithoutAnyNetworkCall() {
        String url = page.baseUrl() + "/";
        when(repo.findByUrl(url)).thenReturn(Optional.of(WebPageCacheEntry.builder()
                .url(url).content("Cached article body.").source("jsoup")
                .fetchedAt(LocalDateTime.now()).build()));

        String result = tool.read_url(url, null);

        assertThat(result).isEqualTo("Cached article body.");
        // Neither reader was touched — the hit short-circuits before any HTTP request.
        assertThat(page.hits.get()).isZero();
        assertThat(firecrawl.hits.get()).isZero();
    }

    @Test
    void cacheMiss_jsoupFetchSucceeds_storesEntry() {
        String url = page.baseUrl() + "/";
        page.body = ARTICLE_HTML;
        when(repo.findByUrl(anyString())).thenReturn(Optional.empty());

        String result = tool.read_url(url, null);

        assertThat(result).contains("full article body text");
        assertThat(page.hits.get()).isOne();
        assertThat(firecrawl.hits.get()).isZero(); // no escalation needed
        // The full cleaned content was stored under the normalized URL with source "jsoup".
        verify(repo).save(Mockito.argThat(e -> e.getUrl().equals(url)
                && e.getContent().contains("full article body text")
                && e.getSource().equals("jsoup")));
    }

    @Test
    void cacheMiss_jsoupEmpty_firecrawlScrapeSucceeds_storesEntryWithFirecrawlSource() {
        String url = page.baseUrl() + "/";
        page.body = "<html><body></body></html>"; // no readable body for Jsoup
        firecrawl.body = """
                {"success":true,"data":{"markdown":"Scraped markdown from Firecrawl."}}
                """;
        when(repo.findByUrl(anyString())).thenReturn(Optional.empty());

        String result = tool.read_url(url, null);

        assertThat(result).contains("Scraped markdown from Firecrawl.");
        assertThat(page.hits.get()).isOne();
        assertThat(firecrawl.hits.get()).isOne();
        verify(repo).save(Mockito.argThat(e -> e.getSource().equals("firecrawl")
                && e.getContent().contains("Scraped markdown from Firecrawl.")));
    }

    @Test
    void emptyPageFromAllReaders_neverCached() {
        String url = page.baseUrl() + "/";
        page.body = "<html><body></body></html>";
        firecrawl.body = "{\"success\":false,\"error\":\"render failed\"}";
        when(repo.findByUrl(anyString())).thenReturn(Optional.empty());

        String result = tool.read_url(url, null);

        assertThat(result).isEmpty(); // no readable content — LLM falls back to search snippets
        verify(repo, never()).save(Mockito.any(WebPageCacheEntry.class));
    }
}
