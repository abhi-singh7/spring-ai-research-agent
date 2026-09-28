package com.researchagent.model.entity;

import com.researchagent.model.enums.StepType;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Embedded step document inside {@link ResearchSession#getSteps()}.
 *
 * <p>Order within the session is given by {@link #orderIndex} (unique per session, kept
 * sequential as steps are appended). Stored as a sub-document of the session — there is no
 * separate collection and no back-reference to the parent.</p>
 */
@Data
public class ResearchStep {

    private UUID id = UUID.randomUUID();

    private Integer orderIndex;

    private StepType type;

    private String content;

    private String status = "PENDING";

    private LocalDateTime createdAt;
}
