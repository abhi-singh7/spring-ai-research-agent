package com.researchagent.service;

import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the cooperative cancellation registry. */
class ResearchCancellationRegistryTest {

    private final ResearchCancellationRegistry registry = new ResearchCancellationRegistry();

    /** A Disposable double that counts how many times it was disposed. */
    private static class CountingDisposable implements Disposable {
        private volatile boolean disposed;

        @Override
        public void dispose() {
            if (!disposed) {
                disposed = true;
                count.incrementAndGet();
            }
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }

        final AtomicInteger count = new AtomicInteger();

        int times() {
            return count.get();
        }
    }

    @Test
    void cancel_flipsFlag_disposesActiveStream_andIsReported() {
        UUID id = UUID.randomUUID();
        ResearchCancellationRegistry.CancellationHandle handle = registry.register(id);

        assertFalse(registry.isCancelled(id));
        assertDoesNotThrow(handle::ensureActive);

        CountingDisposable stream = new CountingDisposable();
        handle.setActiveStream(stream);

        assertTrue(registry.cancel(id));
        assertEquals(1, stream.times()); // the active stream was disposed
        assertTrue(registry.isCancelled(id));
        assertThrows(ResearchCancelledException.class, handle::ensureActive);
    }

    @Test
    void cancel_withoutRegisteredHandle_returnsFalse() {
        assertFalse(registry.cancel(UUID.randomUUID()));
    }

    @Test
    void unregister_removesHandle_soLaterCancelFindsNothing() {
        UUID id = UUID.randomUUID();
        ResearchCancellationRegistry.CancellationHandle handle = registry.register(id);

        registry.unregister(id, handle);

        assertFalse(registry.isCancelled(id)); // unregistered ⇒ no longer tracked as cancelled
        assertFalse(registry.cancel(id));
    }
}
