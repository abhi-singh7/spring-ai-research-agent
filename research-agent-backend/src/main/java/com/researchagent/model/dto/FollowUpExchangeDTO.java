package com.researchagent.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Wire shape for a stored follow-up Q&amp;A exchange (see {@code FollowUpExchange} entity).
 */
@Data
public class FollowUpExchangeDTO {

    private UUID id;
    private String question;
    private String answer;
    private LocalDateTime createdAt;
}
