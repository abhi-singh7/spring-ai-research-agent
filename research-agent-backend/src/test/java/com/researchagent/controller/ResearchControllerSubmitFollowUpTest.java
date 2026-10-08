package com.researchagent.controller;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.researchagent.model.dto.FollowUpRequest;
import com.researchagent.model.entity.FollowUpExchange;
import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.enums.ResearchStatus;
import com.researchagent.service.ResearchOrchestratorService;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.security.test.context.support.WithMockUser;

@WebMvcTest(ResearchController.class)
@WithMockUser
class ResearchControllerSubmitFollowUpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ResearchOrchestratorService orchestratorService;

    private ResearchSession session(ResearchStatus status) {
        ResearchSession session = new ResearchSession();
        session.setId(UUID.randomUUID());
        session.setTopic("Quantum computing");
        session.setStatus(status);
        return session;
    }

    private FollowUpExchange exchange(String question, String answer) {
        FollowUpExchange exchange = new FollowUpExchange();
        exchange.setQuestion(question);
        exchange.setAnswer(answer);
        exchange.setCreatedAt(LocalDateTime.now());
        return exchange;
    }

    @Test
    void submitFollowUp_shouldReturn201WithStoredExchange() throws Exception {
        // Given: a COMPLETED session and the stored exchange returned by the service
        ResearchSession completed = session(ResearchStatus.COMPLETED);
        UUID sessionId = completed.getId();
        FollowUpExchange stored = exchange("What are qubits?", "Based on the research findings, quantum computing uses qubits...");

        when(orchestratorService.getResearch(sessionId)).thenReturn(completed);
        when(orchestratorService.submitFollowUp(eq(sessionId), eq("What are qubits?"))).thenReturn(stored);

        FollowUpRequest request = new FollowUpRequest();
        request.setQuestion("What are qubits?");

        // When + Then: POST returns 201 with the stored exchange (question, answer, id)
        mockMvc.perform(post("/api/research/{sessionId}/followup", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(stored.getId().toString()))
            .andExpect(jsonPath("$.question").value("What are qubits?"))
            .andExpect(content().string(containsString("qubits")));
    }

    @Test
    void submitFollowUp_shouldReturn404WhenSessionMissing() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(orchestratorService.getResearch(sessionId)).thenReturn(null);

        FollowUpRequest request = new FollowUpRequest();
        request.setQuestion("What are qubits?");

        mockMvc.perform(post("/api/research/{sessionId}/followup", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isNotFound());

        verify(orchestratorService, org.mockito.Mockito.never()).submitFollowUp(eq(sessionId), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void submitFollowUp_shouldReturn400WhenSessionNotCompleted() throws Exception {
        ResearchSession processing = session(ResearchStatus.PROCESSING);
        UUID sessionId = processing.getId();
        when(orchestratorService.getResearch(sessionId)).thenReturn(processing);

        FollowUpRequest request = new FollowUpRequest();
        request.setQuestion("Tell me more");

        mockMvc.perform(post("/api/research/{sessionId}/followup", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());

        verify(orchestratorService, org.mockito.Mockito.never()).submitFollowUp(eq(sessionId), eq("Tell me more"));
    }

    @Test
    void submitFollowUp_shouldReturn400WhenQuestionIsBlank() throws Exception {
        // Given: empty question (validation rejects it before the controller body runs)
        FollowUpRequest request = new FollowUpRequest();
        request.setQuestion("");

        mockMvc.perform(post("/api/research/{sessionId}/followup", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void submitFollowUp_shouldReturn400WhenQuestionIsNull() throws Exception {
        // Given: null question (validation rejects it before the controller body runs)
        FollowUpRequest request = new FollowUpRequest();

        mockMvc.perform(post("/api/research/{sessionId}/followup", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }
}
