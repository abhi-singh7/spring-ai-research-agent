package com.researchagent.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA deep-link fallback.
 *
 * <p>The Angular build is served from this jar's static resources, and only {@code /} maps to
 * {@code index.html} (Spring Boot's welcome page). Client-side routes such as
 * {@code /research/history} or {@code /login} therefore 404 on direct load or refresh unless they
 * are forwarded back to the app shell, where the Angular router takes over.</p>
 *
 * <p>Only extension-less SPA routes are forwarded — static assets ({@code *.js}, {@code *.css},
 * ...) and {@code /api/**} are never touched. Keep this list in sync with
 * {@code research-agent-ui/src/app/app.routes.ts}.</p>
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/login", "/research/*", "/research/history/*"})
    public String forwardToAppShell() {
        return "forward:/index.html";
    }
}
