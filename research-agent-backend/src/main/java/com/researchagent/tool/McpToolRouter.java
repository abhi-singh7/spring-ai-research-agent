package com.researchagent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class McpToolRouter {

    private static final Logger log = LoggerFactory.getLogger(McpToolRouter.class);

    private final Map<String, List<String>> routingMap = new LinkedHashMap<>();

    public McpToolRouter() {

        routingMap.put("latest-information", List.of(
                "searxng",
                "web_search"
        ));

        routingMap.put("general-search", List.of(
                "searxng",
                "web_search"
        ));

        routingMap.put("search-fallback", List.of(
                "searxng",
                "web_search"
        ));

        log.info("McpToolRouter initialized with {} routes", routingMap.size());
    }

    public String getPreferredServer(String taskType) {
        List<String> servers = routingMap.get(taskType);
        if (servers == null || servers.isEmpty()) return null;
        return servers.get(0);
    }

    public List<String> getRoutingChain(String taskType) {
        return routingMap.getOrDefault(taskType, List.of());
    }
}