package com.researchagent.model.dto;

import jakarta.validation.constraints.NotBlank;

public class FollowUpRequest {
    @NotBlank(message = "Question cannot be blank")
    private String question;

    public FollowUpRequest() {}

    public FollowUpRequest(String question) {
        this.question = question;
    }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
}
