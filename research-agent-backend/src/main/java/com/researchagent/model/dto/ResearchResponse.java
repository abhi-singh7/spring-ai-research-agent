package com.researchagent.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
public class ResearchResponse {

    private UUID id;
    private String topic;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private String finalReport;
    private String streamUrl;
    private List<StepDTO> steps;
}
