package com.researchagent.controller;

import com.researchagent.security.InvalidTokenException;
import com.researchagent.security.JwtService;
import com.researchagent.service.ResearchStreamingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

@RestController
public class ResearchStreamController {

    private final ResearchStreamingService streamService;
    private final JwtService jwtService;

    public ResearchStreamController(ResearchStreamingService streamService, JwtService jwtService) {
        this.streamService = streamService;
        this.jwtService = jwtService;
    }

    /**
     * SSE endpoint for streaming research progress updates.
     * The client connects here to receive real-time events from the backend.
     *
     * <p>The browser {@code EventSource} API cannot set an {@code Authorization} header, so the JWT
     * travels as a {@code ?token=} query parameter (openspec/changes/add-jwt-authentication). The
     * path is permitted through the security filter chain; this endpoint validates the token and
     * answers 401 JSON <em>before</em> any stream is opened. A valid token opens the stream with
     * behavior identical to the pre-authentication protocol.</p>
     */
    @GetMapping(value = "/api/research/stream/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Object> streamProgress(@PathVariable UUID sessionId,
                                                 @RequestParam(value = "token", required = false) String token) {
        if (token == null || token.isBlank()) {
            return unauthorized("Missing token");
        }
        try {
            jwtService.validate(token);
        } catch (InvalidTokenException e) {
            return unauthorized("Invalid or expired token");
        }
        // Register this connection with the streaming service so it can send events
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(streamService.registerStream(sessionId));
    }

    private ResponseEntity<Object> unauthorized(String message) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
    }
}
