package com.researchagent.tool;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolRouterTest {

    private final McpToolRouter router = new McpToolRouter();

    // ---------- getPreferredServer ----------

    @Test
    void getPreferredServer_latestInformation_shouldReturnSearxng() {
        assertThat(router.getPreferredServer("latest-information")).isEqualTo("searxng");
    }

    @Test
    void getPreferredServer_generalSearch_shouldReturnSearxng() {
        assertThat(router.getPreferredServer("general-search")).isEqualTo("searxng");
    }

    @Test
    void getPreferredServer_searchFallback_shouldReturnSearxng() {
        assertThat(router.getPreferredServer("search-fallback")).isEqualTo("searxng");
    }

    @Test
    void getPreferredServer_unknownTaskType_shouldStillPreferSearxngViaDefaultChain() {
        // Unknown types fall back to the default chain instead of leaving the caller without a backend.
        assertThat(router.getPreferredServer("unknown-task-type")).isEqualTo("searxng");
    }

    // ---------- getRoutingChain / resolveBackends ----------

    @Test
    void getRoutingChain_latestInformation_shouldReturnSearxngThenDuckDuckGoThenOllamaThenTavily() {
        List<String> chain = router.getRoutingChain("latest-information");
        assertThat(chain).containsExactly("searxng", "ddg", "ollama_web_search", "tavily");
    }

    @Test
    void getRoutingChain_generalSearch_shouldReturnSearxngThenDuckDuckGoThenOllamaThenTavily() {
        List<String> chain = router.getRoutingChain("general-search");
        assertThat(chain).containsExactly("searxng", "ddg", "ollama_web_search", "tavily");
    }

    @Test
    void resolveBackends_unknownTaskType_shouldReturnDefaultChainNotEmptyList() {
        // A search must always have at least one backend to try — an empty chain would be a regression.
        assertThat(router.resolveBackends("unknown-task-type")).containsExactly("searxng", "ddg", "ollama_web_search", "tavily");
    }

    @Test
    void resolveBackends_allTaskTypes_shouldEndWithTavilyAsFinalFallback() {
        // Tavily's hosted API is the last escalation target — it must be reachable for every task type.
        // Arrays.asList — unlike List.of — permits the null task type in this list.
        for (String type : Arrays.asList("latest-information", "general-search", "search-fallback", null, "  ", "unknown-task-type")) {
            List<String> chain = router.resolveBackends(type);
            assertThat(chain).isNotEmpty();
            assertThat(chain.get(chain.size() - 1)).isEqualTo("tavily");
        }
    }

    @Test
    void resolveBackends_nullTaskType_shouldReturnDefaultChain() {
        assertThat(router.resolveBackends(null)).isEqualTo(McpToolRouter.DEFAULT_BACKEND_CHAIN);
    }

    @Test
    void resolveBackends_blankTaskType_shouldReturnDefaultChain() {
        assertThat(router.resolveBackends("  ")).isEqualTo(McpToolRouter.DEFAULT_BACKEND_CHAIN);
    }

    // ---------- routing chain consistency ----------

    @Test
    void allKnownTaskTypes_shouldHaveSamePreferredServer() {
        String latestPref = router.getPreferredServer("latest-information");
        String generalPref = router.getPreferredServer("general-search");
        String fallbackPref = router.getPreferredServer("search-fallback");

        assertThat(latestPref).isEqualTo(generalPref);
        assertThat(generalPref).isEqualTo(fallbackPref);
    }

    @Test
    void allKnownTaskTypes_shouldHaveDistinctOrderedBackends() {
        for (String type : List.of("latest-information", "general-search", "search-fallback")) {
            List<String> chain = router.getRoutingChain(type);
            assertThat(chain).hasSizeGreaterThanOrEqualTo(2);
            // The fallback must be a DIFFERENT engine — retrying the same one would defeat escalation.
            assertThat(chain.get(0)).isNotEqualTo(chain.get(1));
        }
    }
}
