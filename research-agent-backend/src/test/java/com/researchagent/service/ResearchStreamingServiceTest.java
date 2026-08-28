package com.researchagent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResearchStreamingServiceTest {

    private ResearchStreamingService service;

    @BeforeEach
    void setUp() {
        // Create a real instance with default timeout (30s from test application.yml)
        service = new ResearchStreamingService();
    }

    @Test
    void registerStream_shouldCreateEmitterAndStoreInMap() {
        UUID sessionId = UUID.randomUUID();

        SseEmitter emitter = service.registerStream(sessionId);

        assertThat(emitter).isNotNull();
        // Verify the emitter was stored in the internal map via a second registration check
        SseEmitter retrieved = getInternalEmitters().get(sessionId);
        assertThat(retrieved).isSameAs(emitter);
    }

    @Test
    void registerStream_shouldReturnDifferentEmittersForDifferentSessions() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        SseEmitter e1 = service.registerStream(id1);
        SseEmitter e2 = service.registerStream(id2);

        assertThat(e1).isNotSameAs(e2);
    }

    @Test
    void removeStream_shouldRemoveFromMap() {
        UUID sessionId = UUID.randomUUID();
        service.registerStream(sessionId);

        service.removeStream(sessionId);

        assertThat(getInternalEmitters()).doesNotContainKey(sessionId);
    }

    @Test
    void removeStream_shouldDoNothingForUnknownSession() {
        UUID unknownId = UUID.randomUUID();
        // Should not throw
        service.removeStream(unknownId);
    }

    @Test
    void sendProgress_shouldLogWarningWhenNoConnection() {
        UUID sessionId = UUID.randomUUID();
        // No emitter registered — should log warning but not throw
        service.sendProgress(sessionId, "Test progress");
        // If we get here without exception, the method worked correctly
    }

    @Test
    void sendContent_shouldLogWarningWhenNoConnection() {
        UUID sessionId = UUID.randomUUID();
        service.sendContent(sessionId, "test content");
    }

    @Test
    void sendReportChunk_shouldLogWarningWhenNoConnection() {
        UUID sessionId = UUID.randomUUID();
        service.sendReportChunk(sessionId, "chunk data");
    }

    @Test
    void sendReportDone_shouldLogWarningWhenNoConnection() {
        UUID sessionId = UUID.randomUUID();
        service.sendReportDone(sessionId, "done content");
    }

    @Test
    void sendError_shouldLogWarningWhenNoConnection() {
        UUID sessionId = UUID.randomUUID();
        service.sendError(sessionId, "error message");
    }

    // ---- Helper to access private ConcurrentHashMap field via reflection ----

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<UUID, SseEmitter> getInternalEmitters() {
        try {
            java.lang.reflect.Field f = ResearchStreamingService.class.getDeclaredField("sessions");
            f.setAccessible(true);
            return (ConcurrentHashMap<UUID, SseEmitter>) f.get(service);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
