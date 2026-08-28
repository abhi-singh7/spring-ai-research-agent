package com.researchagent.service;

import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.enums.ResearchStatus;
import com.researchagent.repository.ResearchSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Background scheduler that periodically marks abandoned PROCESSING sessions as CANCELLED.
 * <p>
 * A session is considered "abandoned" if it has been stuck in PROCESSING state for longer than
 * the configured stale threshold (default: 1 hour). The scheduler runs at a fixed interval
 * (default: every 30 minutes) and updates matching sessions to CANCELLED status, preserving
 * history data while removing stale "Processing..." entries from user-facing views.
 */
@Component
public class AbandonedSessionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(AbandonedSessionCleanupService.class);

    private final ResearchSessionRepository sessionRepo;

    @Value("${app.cleanup.stale-after:PT1H}")
    private Duration staleAfter;

    public AbandonedSessionCleanupService(ResearchSessionRepository sessionRepo) {
        this.sessionRepo = sessionRepo;
    }

    /**
     * Log scheduler configuration at startup to verify it is registered.
     */
    @PostConstruct
    void logSchedulerConfig() {
        log.info("AbandonedSessionCleanupService initialized — stale-after={}, interval={}", staleAfter, appCleanupInterval());
    }

    @Value("${app.cleanup.interval:PT30M}")
    private Duration interval;

    private String appCleanupInterval() {
        return String.valueOf(interval);
    }

    /**
     * Find and mark abandoned PROCESSING sessions as CANCELLED.
     */
    @Scheduled(fixedRateString = "${app.cleanup.interval:PT30M}")
    public void cleanupAbandonedSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minus(staleAfter);
        List<ResearchSession> abandoned = sessionRepo.findAllByStatusAndCreatedAtBefore(
                ResearchStatus.PROCESSING, cutoff);

        int cleanedCount = 0;
        for (ResearchSession session : abandoned) {
            try {
                session.setStatus(ResearchStatus.CANCELLED);
                sessionRepo.save(session);
                cleanedCount++;
                log.debug("Marked abandoned session as CANCELLED: {}", session.getId());
            } catch (Exception e) {
                log.error("Failed to clean up session {}: {}", session.getId(), e.getMessage());
            }
        }

        log.info("Abandoned session cleanup complete. Found {} stale sessions, marked {} as CANCELLED.",
                abandoned.size(), cleanedCount);
    }
}
