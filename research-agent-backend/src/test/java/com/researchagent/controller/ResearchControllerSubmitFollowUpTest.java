package com.researchagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.researchagent.model.dto.FollowUpRequest;
import com.researchagent.service.ResearchOrchestratorService;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ResearchController.class)
class ResearchControllerSubmitFollowUpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ResearchOrchestratorService orchestratorService;

    @Test
    void submitFollowUp_shouldReturn200WithAnswer() throws Exception {
        // Given: a valid follow-up question and LLM answer from service
        UUID sessionId = UUID.randomUUID();
        String answer = "Based on the research findings, quantum computing uses qubits...";

        when(orchestratorService.submitFollowUp(eq(sessionId), eq("What are qubits?")))
            .thenReturn(answer);

        FollowUpRequest request = new FollowUpRequest();
        request.setQuestion("What are qubits?");

        // When + Then: POST returns 200 with the LLM answer
        mockMvc.perform(post("/api/research/{sessionId}/followup", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("qubits")));
    }

    @Test
    void submitFollowUp_shouldReturn400WhenQuestionIsBlank() throws Exception {
        // Given: empty question (validation rejects it)
        FollowUpRequest request = new FollowUpRequest();
        request.setQuestion("");

        mockMvc.perform(post("/api/research/{sessionId}/followup", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void submitFollowUp_shouldReturn400WhenQuestionIsNull() throws Exception {
        // Given: null question (validation rejects it)
        FollowUpRequest request = new FollowUpRequest();

        mockMvc.perform(post("/api/research/{sessionId}/followup", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void submitFollowUp_shouldPropagateExceptionWhenSessionNotCompleted() {
        // Given: service throws exception for non-completed session — no global handler exists
        UUID sessionId = UUID.randomUUID();
        when(orchestratorService.submitFollowUp(eq(sessionId), eq("Tell me more")))
            .thenThrow(new IllegalArgumentException("Can only ask follow-up questions for completed sessions"));

        FollowUpRequest request = new FollowUpRequest();
        request.setQuestion("Tell me more");

        // When + Then: the exception propagates (no @ExceptionHandler for it)
        org.junit.jupiter.api.Assertions.assertThrows(
            jakarta.servlet.ServletException.class,
            () -> mockMvc.perform(post("/api/research/{sessionId}/followup", sessionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
        );

        verify(orchestratorService).submitFollowUp(eq(sessionId), eq("Tell me more"));
    }
}
