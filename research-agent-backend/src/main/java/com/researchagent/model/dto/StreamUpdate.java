package com.researchagent.model.dto;

import lombok.Data;
import java.util.UUID;

@Data
public class StreamUpdate {

    public enum EventType {
        PROGRESS, CONTENT, REPORT_CHUNK, REPORT_DONE, STEP_COMPLETE, ERROR, REPORT_START
    }

    private EventType type;
    private UUID sessionId;
    private Object payload;   // String for PROGRESS/CONTENT/REPORT_CHUNK, ReportDTO for REPORT_DONE
}
