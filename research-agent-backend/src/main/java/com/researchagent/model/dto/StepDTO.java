package com.researchagent.model.dto;

import com.researchagent.model.enums.StepType;
import lombok.Data;

@Data
public class StepDTO {

    private Integer orderIndex;
    private StepType type;
    private String status;
    private String content;
}
