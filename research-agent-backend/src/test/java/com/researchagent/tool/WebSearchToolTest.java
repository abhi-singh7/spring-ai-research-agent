package com.researchagent.tool;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the search tool executes the routed fallback chain IN ONE CALL: Firecrawl first (self-hosted
 * API), DuckDuckGo as an independent escalation target, Ollama Web Search (hosted API) third, Tavily
 * (hosted search API) as the final one — and that a decisive "all backends exhausted" verdict is returned
 * (so the LLM never retries the same dead search method in a loop).
 */
class WebSearchToolTest {

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
                    exchange.close(); // no-body response (status -1) still needs the exchange closed
                }
            });
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        @Override public void close() { server.stop(0); }
    }

    private StubServer firecrawl;
    private StubServer ddg;
    private StubServer ollama;
    private StubServer tavily;
    private WebSearchTool tool;

    /** Firecrawl v2/search success payload: {"success":true,"data":{"web":[{url,title,description}]}} */
    private static final String FIRECRAWL_JSON = """
            {"success":true,"data":{"web":[
              {"url":"https://a.example/1","title":"FC Title","description":"FC snippet"},
              {"url":"https://a.example/2","title":"FC Two","description":"Second"}
            ]}}""";

    private static final String DDG_HTML = """
            <html><body>
            <div class="result"><a rel="nofollow" href="/l/?uddg=https%3A%2F%2Fexample.org%2Fguide&rut=abc123" \
                 class="result__a">Duck <b>Duck</b> Guide</a></div>
            <div><span class="result__snippet">An overview of the <b>topic</b>.</span></div>
            <div class="result"><a rel="nofollow" href="/l/?uddg=https%3A%2F%2Fexample.org%2Fguide&rut=abc124" \
                 class="result__a">Duplicate entry</a></div>
            <div><span class="result__snippet">Second snippet.</span></div>
            </body></html>
            """;

    private static final String OLLAMA_JSON = """
            {"results":[
              {"title":"Ollama Result","url":"https://o.example/1","content":"From the hosted API"},
              {"title":"Second Ollama Hit","url":"https://o.example/2","content":""}
            ]}""";

    private static final String TAVILY_JSON = """
            {"query":"what is Z","results":[
              {"title":"Tavily Result","url":"https://t.example/1","content":"From the Tavily API"},
              {"title":"Second Tavily Hit","url":"https://t.example/2","content":""}
            ],"answer":null}""";

    @BeforeEach
    void setUp() throws IOException {
        firecrawl = new StubServer("/v2/search");
        ddg = new StubServer("/html/");
        ollama = new StubServer("/api/web_search");
        tavily = new StubServer("/search");
        firecrawl.start();
        ddg.start();
        ollama.start();
        tavily.start();
        tool = new WebSearchTool(new McpToolRouter(), firecrawl.baseUrl(), ddg.baseUrl(),
                ollama.baseUrl(), "test-api-key", tavily.baseUrl(), "tavily-test-key");
    }

    @AfterEach
    void tearDown() throws IOException {
        firecrawl.close();
        ddg.close();
        ollama.close();
        tavily.close();
    }

    @Test
    void firecrawlHasResults_returnsFirecrawlAndSkipsDuckDuckGo() {
        firecrawl.body = FIRECRAWL_JSON;

        String result = tool.search("how does X work", "general-search");

        assertThat(result).contains("FC Title").contains("https://a.example/1").contains("FC snippet");
        assertThat(firecrawl.hits.get()).isOne();
        // Escalation target must NOT be touched when the preferred engine already succeeded.
        assertThat(ddg.hits.get()).isZero();
    }

    @Test
    void firecrawlEmpty_escalatesToDuckDuckGo_inSameCall() {
        firecrawl.body = "{\"success\":true,\"data\":{\"web\":[]}}";
        ddg.status = 200;
        ddg.body = DDG_HTML;

        String result = tool.search("latest news about Y", "latest-information");

        // DuckDuckGo results must be formatted and returned...
        assertThat(result).contains("Duck Duck Guide")      // <b> tags stripped from title
                .contains("https://example.org/guide")       // uddg URL decoded once (deduped)
                .contains("An overview of the topic.");      // snippet tag-stripped

        // ...and both backends were actually consulted in this single call.
        assertThat(firecrawl.hits.get()).isOne();
        assertThat(ddg.hits.get()).isOne();
    }

    @Test
    void firecrawlReportsFailure_escalatesToDuckDuckGo_inSameCall() {
        // success:false with an error message — the chain must treat it as a backend failure.
        firecrawl.body = "{\"success\":false,\"error\":\"rate limited\"}";
        ddg.status = 200;
        ddg.body = DDG_HTML;

        String result = tool.search("what is Z", null);

        assertThat(result).contains("Duck Duck Guide");
        assertThat(firecrawl.hits.get()).isOne();
        assertThat(ddg.hits.get()).isOne();
    }

    @Test
    void allBackendsFail_returnsDecisiveExhaustionVerdict() {
        firecrawl.status = 500;
        ddg.status = 403;
        ddg.body = "blocked";

        ollama.status = 401;
        // tavily stub keeps its default empty body → "empty response"

        String result = tool.search("impossible query", null);

        assertThat(result).startsWith("No results from any search backend");
        // Per-backend reasons make the failure diagnostic (and signal: nothing to retry against).
        assertThat(result).contains("firecrawl").contains("ddg").contains("ollama_web_search").contains("tavily");
        // Explicit anti-retry instruction so the LLM stops hammering search.
        assertThat(result).contains("Do NOT retry this query");
    }

    @Test
    void firecrawlEmpty_ddgBlocked_escalatesToOllama_inSameCall() {
        firecrawl.body = "{\"success\":true,\"data\":{\"web\":[]}}";
        ddg.status = 403;   // rate-limited / bot-blocked — the exact trigger for escalation
        ollama.body = OLLAMA_JSON;

        String result = tool.search("what is Z", null);

        // Ollama's hosted results must be formatted and returned...
        assertThat(result).contains("Ollama Result")
                .contains("https://o.example/1")          // URL forwarded as captured
                .contains("From the hosted API");         // content field mapped to snippet line

        // ...and all three backends were actually consulted in this single call.
        assertThat(firecrawl.hits.get()).isOne();
        assertThat(ddg.hits.get()).isOne();
        assertThat(ollama.hits.get()).isOne();
        // Escalation stops at the first successful backend — Tavily must never be touched here.
        assertThat(tavily.hits.get()).isZero();
    }

    @Test
    void firecrawlEmpty_ddgBlocked_ollamaFails_escalatesToTavily_inSameCall() {
        firecrawl.body = "{\"success\":true,\"data\":{\"web\":[]}}";
        ddg.status = 403;   // rate-limited / bot-blocked
        ollama.status = 500;
        tavily.body = TAVILY_JSON;

        String result = tool.search("what is Z", null);

        // Tavily's hosted results must be formatted and returned...
        assertThat(result).contains("Tavily Result")
                .contains("https://t.example/1")          // URL forwarded as captured
                .contains("From the Tavily API");         // content field mapped to snippet line

        // ...and all four backends were actually consulted in this single call.
        assertThat(firecrawl.hits.get()).isOne();
        assertThat(ddg.hits.get()).isOne();
        assertThat(ollama.hits.get()).isOne();
        assertThat(tavily.hits.get()).isOne();
    }

    @Test
    void ollamaApiKeyMissing_backendReportsNotConfiguredWithoutCallingOut() {
        WebSearchTool noKey = new WebSearchTool(new McpToolRouter(), firecrawl.baseUrl(), ddg.baseUrl(),
                ollama.baseUrl(), "   ", tavily.baseUrl(), "tavily-test-key");
        firecrawl.body = "{\"success\":true,\"data\":{\"web\":[]}}";
        ddg.status = 200; // empty page → no results
        ddg.body = "<html><body>nothing</body></html>";

        String result = noKey.search("what is Z", null);

        assertThat(result).startsWith("No results from any search backend");
        assertThat(result).contains("ollama_web_search: OLLAMA_API_KEY not configured");
        // No network call was made to the hosted API — the config gap short-circuits before connecting.
        assertThat(ollama.hits.get()).isZero();
    }

    @Test
    void tavilyApiKeyMissing_backendReportsNotConfiguredWithoutCallingOut() {
        WebSearchTool noKey = new WebSearchTool(new McpToolRouter(), firecrawl.baseUrl(), ddg.baseUrl(),
                ollama.baseUrl(), "test-api-key", tavily.baseUrl(), "   ");
        firecrawl.body = "{\"success\":true,\"data\":{\"web\":[]}}";
        ddg.status = 403; // blocked — escalation continues
        ollama.status = 500;

        String result = noKey.search("what is Z", null);

        assertThat(result).startsWith("No results from any search backend");
        assertThat(result).contains("tavily: TAVILY_API_KEY not configured");
        // No network call was made to the Tavily API — the config gap short-circuits before connecting.
        assertThat(tavily.hits.get()).isZero();
    }

    @Test
    void firecrawlDown_ddgWorks_stillSucceedsWithIndependentEngine() {
        // Simulate a dead Firecrawl process: connection refused (nothing listening on that port).
        WebSearchTool toolWithDeadFirecrawl = new WebSearchTool(
                new McpToolRouter(), "http://127.0.0.1:1", ddg.baseUrl(), ollama.baseUrl(), "test-api-key",
                tavily.baseUrl(), "tavily-test-key");
        ddg.body = DDG_HTML;
        firecrawl.close();

        String result = toolWithDeadFirecrawl.search("what is Z", null);

        assertThat(result).contains("Duck Duck Guide");
        assertThat(ddg.hits.get()).isOne();
        // Escalation stops at the first successful backend — Ollama and Tavily must never be touched here.
        assertThat(ollama.hits.get()).isZero();
        assertThat(tavily.hits.get()).isZero();
    }
}
