package com.researchagent.model.entity;


import com.fasterxml.jackson.annotation.JsonIgnore;
import com.researchagent.model.enums.ResearchStatus;
import lombok.Data;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;


import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Research session document (collection {@code research_session}).
 *
 * <p>Steps are EMBEDDED in this document: there is no separate step collection, no lazy
 * loading and no fetch-join. Every {@code save()} is a single atomic document replace that
 * writes the session together with its full step list.</p>
 */
@Data
@Document(collection = "research_session")
@CompoundIndex(name = "status_createdAt", def = "{ status: 1, createdAt: -1 }")
public class ResearchSession {

    @Id
    private UUID id = UUID.randomUUID();

    private String topic;

    private ResearchStatus status = ResearchStatus.PENDING;

    private String prompt;

    @JsonIgnore
    @ToString.Exclude
    private List<ResearchStep> steps = new ArrayList<>();

    private String finalReport;

    /**
     * Follow-up Q&amp;A exchanges, embedded and kept in chronological order. {@code @JsonIgnore}
     * mirrors {@code steps}: the history list endpoints serialize this entity directly and must not
     * bloat every row with full answers — detail/thread endpoints build explicit DTOs.
     */
    @JsonIgnore
    @ToString.Exclude
    private List<FollowUpExchange> followUps = new ArrayList<>();

    @Indexed
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    /**
     * Convenience method to add a step to the embedded list.
     */
    public void addStep(ResearchStep step) {
        if (steps == null) {
            steps = new ArrayList<>();
        }
        steps.add(step);
    }

    /**
     * Convenience method to append a follow-up exchange to the embedded list.
     */
    public void addFollowUp(FollowUpExchange exchange) {
        if (followUps == null) {
            followUps = new ArrayList<>();
        }
        followUps.add(exchange);
    }

    /**
     * Mark the session as completed with a timestamp. No-op if already in a terminal state —
     * late callbacks must never overwrite CANCELLED/FAILED runs.
     */
    public void complete() {
        if (isTerminal()) {
            return;
        }
        this.status = ResearchStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * Mark the session as failed with an error message. No-op if already in a terminal state.
     */
    public void fail(String errorMessage) {
        if (isTerminal()) {
            return;
        }
        this.status = ResearchStatus.FAILED;
        this.finalReport = "Failed: " + errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    private boolean isTerminal() {
        return status == ResearchStatus.COMPLETED
                || status == ResearchStatus.FAILED
                || status == ResearchStatus.CANCELLED;
    }
}
