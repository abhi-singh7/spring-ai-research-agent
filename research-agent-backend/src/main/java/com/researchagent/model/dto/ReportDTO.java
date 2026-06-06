package com.researchagent.model.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class ReportDTO {

    private UUID sessionId;
    private String topic;
    private String reportContent;
    private LocalDateTime createdAt;
}
