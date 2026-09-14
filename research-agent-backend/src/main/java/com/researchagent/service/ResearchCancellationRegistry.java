package com.researchagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory registry of in-flight research pipelines, backing cooperative cancellation.
 *
 * <p>Each running pipeline registers a {@link CancellationHandle} at start and checks it (via
 * {@code ensureActive()}) before every sub-topic round and LLM call attempt; the handle also holds
 * the active report-stream subscription so cancellation can dispose it mid-flight. In-memory by
 * design: the registry only needs to live for as long as a pipeline runs — persistence of the
 * CANCELLED state is done atomically by {@code ResearchOrchestratorService.cancelResearch}.</p>
 */
@Component
public class ResearchCancellationRegistry {

    private static final Logger log = LoggerFactory.getLogger(ResearchCancellationRegistry.class);

    /** Mutable cancellation state for one pipeline run: the cancel flag + any active stream subscription. */
    public static final class CancellationHandle {

        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        /** The report-stream subscription, once streaming has started (null until then). */
        private volatile Disposable activeStream;

        /** Throws {@link ResearchCancelledException} if this run has been cancelled. */
        public void ensureActive() {
            if (cancelled.get()) {
                throw new ResearchCancelledException();
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        void markCancelled() {
            cancelled.set(true);
        }

        void setActiveStream(Disposable d) {
            this.activeStream = d;
        }

        Disposable getActiveStream() {
            return activeStream;
        }
    }

    private final ConcurrentHashMap<UUID, CancellationHandle> handles = new ConcurrentHashMap<>();

    /** Register a pipeline run. Called at the top of {@code processResearchAsync}. */
    public CancellationHandle register(UUID sessionId) {
        CancellationHandle handle = new CancellationHandle();
        handles.put(sessionId, handle);
        return handle;
    }

    /**
     * Signal cancellation to an in-flight run: flips the flag (stops all further checkpoints) and
     * disposes any active report-stream subscription (cancels its upstream HTTP request).
     *
     * @return true if a live handle was found and signalled
     */
    public boolean cancel(UUID sessionId) {
        CancellationHandle handle = handles.get(sessionId);
        if (handle == null) {
            return false;
        }
        handle.markCancelled();
        Disposable stream = handle.getActiveStream();
        if (stream != null && !stream.isDisposed()) {
            try {
                stream.dispose();
            } catch (RuntimeException e) {
                log.warn("Failed to dispose active report stream for session {}: {}", sessionId, e.getMessage());
            }
        }
        return true;
    }

    /** True if the given run is registered AND has been cancelled. */
    public boolean isCancelled(UUID sessionId) {
        CancellationHandle handle = handles.get(sessionId);
        return handle != null && handle.isCancelled();
    }

    /** Remove once the owning pipeline has fully terminated (idempotent; only removes this exact handle). */
    public void unregister(UUID sessionId, CancellationHandle expected) {
        if (expected == null) {
            return;
        }
        handles.remove(sessionId, expected);
    }
}
