package com.researchagent.model.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A single follow-up Q&amp;A exchange, EMBEDDED in the {@code research_session} document.
 *
 * <p>Stored in chronological order on {@link ResearchSession#getFollowUps()}. Like steps, it is
 * part of the session document: every save is one atomic replace and deleting the session removes
 * its exchanges with it — no separate collection, no cascade.</p>
 */
@Data
public class FollowUpExchange {

    private UUID id = UUID.randomUUID();

    private String question;

    private String answer;

    private LocalDateTime createdAt;
}
