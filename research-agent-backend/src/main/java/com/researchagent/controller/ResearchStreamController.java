package com.researchagent.controller;

import com.researchagent.service.ResearchStreamingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
public class ResearchStreamController {

    @Autowired
    private ResearchStreamingService streamService;

    /**
     * SSE endpoint for streaming research progress updates.
     * The client connects here to receive real-time events from the backend.
     */
    @GetMapping(value = "/api/research/stream/{sessionId}", produces = "text/event-stream")
    public SseEmitter streamProgress(@PathVariable UUID sessionId) {
        // Register this connection with the streaming service so it can send events
        return streamService.registerStream(sessionId);
    }
}
