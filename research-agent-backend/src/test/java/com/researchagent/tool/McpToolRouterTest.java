package com.researchagent.tool;

import org.junit.jupiter.api.Test;

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
    void getPreferredServer_unknownTaskType_shouldReturnNull() {
        assertThat(router.getPreferredServer("unknown-task-type")).isNull();
    }

    // ---------- getRoutingChain ----------

    @Test
    void getRoutingChain_latestInformation_shouldReturnSearxngThenWebSearch() {
        List<String> chain = router.getRoutingChain("latest-information");
        assertThat(chain).containsExactly("searxng", "web_search");
    }

    @Test
    void getRoutingChain_generalSearch_shouldReturnSearxngThenWebSearch() {
        List<String> chain = router.getRoutingChain("general-search");
        assertThat(chain).containsExactly("searxng", "web_search");
    }

    @Test
    void getRoutingChain_unknownTaskType_shouldReturnEmptyList() {
        List<String> chain = router.getRoutingChain("unknown-task-type");
        assertThat(chain).isEmpty();
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
}
