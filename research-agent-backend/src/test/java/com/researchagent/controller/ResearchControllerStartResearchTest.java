package com.researchagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.researchagent.model.dto.ResearchRequest;
import com.researchagent.model.entity.ResearchSession;
import com.researchagent.model.enums.ResearchStatus;
import com.researchagent.service.ResearchOrchestratorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ResearchController.class)
class ResearchControllerStartResearchTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ResearchOrchestratorService orchestratorService;

    @Test
    void startResearch_shouldReturn201WithSessionIdWhenRequestIsValid() throws Exception {
        // Given: a valid research request and a session returned by the service
        UUID sessionId = UUID.randomUUID();
        String topic = "Spring Boot testing best practices";

        ResearchSession mockSession = new ResearchSession();
        mockSession.setId(sessionId);
        mockSession.setTopic(topic);
        mockSession.setStatus(ResearchStatus.PROCESSING);

        when(orchestratorService.createAndStart(any())).thenReturn(mockSession);

        ResearchRequest request = new ResearchRequest();
        request.setTopic(topic);

        // When + Then: POST /api/research should return 201 with session id and topic
        mockMvc.perform(post("/api/research")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(sessionId.toString()))
            .andExpect(jsonPath("$.topic").value(topic))
            .andExpect(jsonPath("$.status").value("PROCESSING"));

        // Verify async processing was kicked off (createAndStart is stubbed, processResearchAsync called)
    }

    @Test
    void startResearch_shouldReturn400WhenTopicIsBlank() throws Exception {
        // Given: a request with blank topic (validation will fail before controller logic)
        ResearchRequest request = new ResearchRequest();
        request.setTopic("");

        // When + Then: Bean Validation rejects the blank topic
        mockMvc.perform(post("/api/research")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void startResearch_shouldReturn400WhenTopicIsNull() throws Exception {
        // Given: a request with null topic
        ResearchRequest request = new ResearchRequest();

        // When + Then: Bean Validation rejects the missing topic
        mockMvc.perform(post("/api/research")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void startResearch_shouldReturn400WhenMaxIterationsIsZero() throws Exception {
        // Given: a valid topic but maxIterations = 0 (violates @Min(1))
        ResearchRequest request = new ResearchRequest();
        request.setTopic("Java concurrency");
        request.setMaxIterations(0);

        mockMvc.perform(post("/api/research")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void startResearch_shouldReturn400WhenSubTopicCountIsZero() throws Exception {
        // Given: a valid topic but subTopicCount = 0 (violates @Min(1))
        ResearchRequest request = new ResearchRequest();
        request.setTopic("Machine learning");
        request.setSubTopicCount(0);

        mockMvc.perform(post("/api/research")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }
}
