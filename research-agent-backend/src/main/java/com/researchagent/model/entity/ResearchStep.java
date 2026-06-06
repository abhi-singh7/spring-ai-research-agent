package com.researchagent.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.researchagent.model.enums.StepType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "research_step", uniqueConstraints = @UniqueConstraint(columnNames = {"session_id", "order_index"}))
public class ResearchStep {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false, referencedColumnName = "id")
    @JsonIgnoreProperties("steps")
    @ToString.Exclude
    private ResearchSession session;

    @Column(nullable = false)
    private Integer orderIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StepType type;

   // @Lob
    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 32)
    private String status = "PENDING";

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
