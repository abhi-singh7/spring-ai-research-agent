package com.researchagent.service;

import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.enums.ResearchStatus;
import com.researchagent.repository.ResearchSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest
class AbandonedSessionCleanupServiceTest {

    @Autowired
    private AbandonedSessionCleanupService cleanupService;

    @MockBean
    private ResearchSessionRepository sessionRepo;

    private UUID staleId1, staleId2, recentId;
    private ResearchSession staleSession1, staleSession2, recentSession;

    @BeforeEach
    void setUp() {
        staleId1 = UUID.randomUUID();
        staleId2 = UUID.randomUUID();
        recentId = UUID.randomUUID();

        LocalDateTime twoHoursAgo = LocalDateTime.now().minusHours(2);
        LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);

        staleSession1 = createSession(staleId1, ResearchStatus.PROCESSING, twoHoursAgo);
        staleSession2 = createSession(staleId2, ResearchStatus.PROCESSING, twoHoursAgo);
        recentSession = createSession(recentId, ResearchStatus.PROCESSING, tenMinutesAgo);
    }

    private ResearchSession createSession(UUID id, ResearchStatus status, LocalDateTime createdAt) {
        ResearchSession session = new ResearchSession();
        session.setId(id);
        session.setStatus(status);
        // Use reflection to set createdAt since it's package-private or protected
        try {
            java.lang.reflect.Field f = ResearchSession.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(session, createdAt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return session;
    }

    @Test
    void cleanupAbandonedSessions_marksStaleProcessingAsCancelled() {
        // Given: repository query returns only stale sessions (recent one is filtered out by cutoff)
        List<ResearchSession> staleOnly = List.of(staleSession1, staleSession2);
        when(sessionRepo.findAllByStatusAndCreatedAtBefore(eq(ResearchStatus.PROCESSING), any())).thenReturn(staleOnly);

        // When: cleanup runs
        cleanupService.cleanupAbandonedSessions();

        // Then: stale sessions have status CANCELLED
        assertThat(staleSession1.getStatus())
                .as("stale session 1 should be CANCELLED")
                .isEqualTo(ResearchStatus.CANCELLED);
        assertThat(staleSession2.getStatus())
                .as("stale session 2 should be CANCELLED")
                .isEqualTo(ResearchStatus.CANCELLED);

        // Verify save was called for both stale sessions returned by query
        verify(sessionRepo).save(same(staleSession1));
        verify(sessionRepo).save(same(staleSession2));
    }

    @Test
    void cleanupAbandonedSessions_doesNothingWhenNoStaleSessions() {
        // Given: no stale sessions found
        when(sessionRepo.findAllByStatusAndCreatedAtBefore(eq(ResearchStatus.PROCESSING), any())).thenReturn(List.of());

        // When
        cleanupService.cleanupAbandonedSessions();

        // Then: no saves called, no errors thrown
        verify(sessionRepo, never()).save(any());
    }
}
