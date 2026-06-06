package com.researchagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchagent.model.dto.StreamUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ResearchStreamingService {

    private static final Logger log = LoggerFactory.getLogger(ResearchStreamingService.class);

    // Thread-safe map: sessionId -> SseEmitter
    private final ConcurrentHashMap<UUID, SseEmitter> sessions = new ConcurrentHashMap<>();

    @Value("${spring.ai.sse.timeout:600000}")  // 10 minutes default timeout
    private long timeoutMs;

    /**
     * Register a new SSE connection for the given session.
     */
    public SseEmitter registerStream(UUID sessionId) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        sessions.put(sessionId, emitter);

        // Clean up on completion or timeout
        emitter.onCompletion(() -> sessions.remove(sessionId));
        emitter.onError(e -> sessions.remove(sessionId));
        emitter.onTimeout(() -> sessions.remove(sessionId));

        return emitter;
    }

    /**
     * Remove a stream connection.
     */
    public void removeStream(UUID sessionId) {
        SseEmitter removed = sessions.remove(sessionId);
        if (removed != null) {
            // Try to complete the emitter; handle already-completed or error cases gracefully
            try {
                removed.complete();
            } catch (IllegalStateException e) {
                log.warn("SSE stream for session {} was already completed: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * Send a typed event to the session's SSE stream.
     */
    private void sendEvent(UUID sessionId, StreamUpdate.EventType type, Object payload) {
        SseEmitter emitter = getSession(sessionId);
        if (emitter == null) {
            log.warn("No SSE connection found for session {}", sessionId);
            return;
        }

        try {
            StreamUpdate update = new StreamUpdate();
            update.setType(type);
            update.setSessionId(sessionId);
            update.setPayload(payload);

            String jsonData = objectMapper.writeValueAsString(update);
            emitter.send(SseEmitter.event()
                    .name(type.name())
                    .data(jsonData));
        } catch (IOException e) {
            log.warn("Error sending SSE event for session {}: {}", sessionId, e.getMessage());
            sessions.remove(sessionId);  // Connection lost, clean up
        }
    }

    /**
     * Send a progress update.
     */
    public void sendProgress(UUID sessionId, String message) {
        sendEvent(sessionId, StreamUpdate.EventType.PROGRESS, message);
    }

    /**
     * Send streaming content chunk (sub-topic findings).
     */
    public void sendContent(UUID sessionId, String content) {
        sendEvent(sessionId, StreamUpdate.EventType.CONTENT, content);
    }

    /**
     * Send a report chunk during final report generation.
     */
    public void sendReportChunk(UUID sessionId, String chunk) {
        sendEvent(sessionId, StreamUpdate.EventType.REPORT_CHUNK, chunk);
    }

    /**
     * Signal that the final report has been generated.
     */
    public void sendReportDone(UUID sessionId, String reportContent) {
        sendEvent(sessionId, StreamUpdate.EventType.REPORT_DONE, reportContent);
    }

    /**
     * Send a step completion notification.
     */
    public void sendStepComplete(UUID sessionId, Map<String, Object> details) {
        sendEvent(sessionId, StreamUpdate.EventType.STEP_COMPLETE, details);
    }

    /**
     * Signal that report generation has started.
     */
    public void sendReportStart(UUID sessionId) {
        sendEvent(sessionId, StreamUpdate.EventType.REPORT_START, null);
    }

    /**
     * Send an error event to the session stream.
     */
    public void sendError(UUID sessionId, String errorMessage) {
        sendEvent(sessionId, StreamUpdate.EventType.ERROR, errorMessage);
    }

    private SseEmitter getSession(UUID sessionId) {
        return sessions.get(sessionId);  // Caller must have sessionId available
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
}
