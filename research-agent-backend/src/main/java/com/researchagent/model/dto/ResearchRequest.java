package com.researchagent.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class ResearchRequest {

    @NotBlank(message = "Topic is required")
    private String topic;

    @Min(value = 1, message = "Max iterations must be at least 1")
    private Integer maxIterations = 3;

    @Min(value = 1, message = "Sub-topic count must be at least 1")
    private Integer subTopicCount = 5;

    private boolean streamingEnabled = true;
}
