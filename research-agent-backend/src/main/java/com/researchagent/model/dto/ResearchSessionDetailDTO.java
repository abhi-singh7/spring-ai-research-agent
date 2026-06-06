package com.researchagent.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
public class ResearchSessionDetailDTO {

    private UUID id;
    private String topic;
    private String status;
    private String prompt;
    private List<StepDTO> steps;
    private String finalReport;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
