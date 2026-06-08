package com.researchagent.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.researchagent.model.enums.ResearchStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Entity
@Table(name = "research_session")
public class ResearchSession {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    private UUID id;

    @Column(nullable = false, length = 1024)
    private String topic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ResearchStatus status = ResearchStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String prompt;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    @ToString.Exclude
    private List<ResearchStep> steps = new ArrayList<>();

    //@Lob
    @Column(columnDefinition = "TEXT")
    private String finalReport;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Convenience method to add a step and maintain bidirectional relationship.
     */
    public void addStep(ResearchStep step) {
        steps.add(step);
        step.setSession(this);
    }

    /**
     * Mark the session as completed with a timestamp.
     */
    public void complete() {
        this.status = ResearchStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * Mark the session as failed with an error message.
     */
    public void fail(String errorMessage) {
        this.status = ResearchStatus.FAILED;
        this.finalReport = "Failed: " + errorMessage;
        this.completedAt = LocalDateTime.now();
    }
}
