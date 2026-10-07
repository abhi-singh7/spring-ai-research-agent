package com.researchagent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Unauthenticated liveness probe.
 *
 * <p>Every {@code /api/research/**} endpoint requires a Bearer JWT since the auth workstream,
 * so deploy/monitoring health checks can no longer poll them (a 401 is not "up"). This endpoint
 * is permitted through the security chain and answers 200 as soon as Tomcat is serving —
 * the GitHub Actions deploy workflow's "Verify service is up" gate polls it.</p>
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
