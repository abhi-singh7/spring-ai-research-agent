package com.researchagent.security;

import com.researchagent.service.ResearchStreamingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end security tests against the REAL filter chain (full application context, real
 * MongoDB): unauthenticated research calls are rejected with 401 JSON, valid Bearer tokens are
 * accepted, and the SSE stream validates its {@code ?token=} query parameter before opening.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ResearchStreamingService streamingService;

    @Value("${app.security.jwt.secret}")
    private String jwtSecret;

    @Value("${app.security.jwt.issuer:research-agent}")
    private String jwtIssuer;

    @Test
    void unauthenticatedResearchCall_returns401Json() throws Exception {
        mockMvc.perform(get("/api/research/history"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void garbageTokenOnResearchCall_returns401Json() throws Exception {
        mockMvc.perform(get("/api/research/history").header("Authorization", "Bearer not.a.real.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void expiredTokenOnResearchCall_returns401Json() throws Exception {
        // Mint a token with the app's own secret/issuer but a negative expiry
        JwtService expiringIssuer = new JwtService(jwtSecret, -10, jwtIssuer);
        String expiredToken = expiringIssuer.issue("alice", "USER");

        mockMvc.perform(get("/api/research/history").header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void validBearerToken_allowsResearchCall() throws Exception {
        String token = jwtService.issue("alice", "USER");

        mockMvc.perform(get("/api/research/history").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void streamWithoutToken_returns401BeforeSseOpens() throws Exception {
        UUID sessionId = UUID.randomUUID();

        mockMvc.perform(get("/api/research/stream/{sessionId}", sessionId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void streamWithInvalidToken_returns401BeforeSseOpens() throws Exception {
        UUID sessionId = UUID.randomUUID();

        mockMvc.perform(get("/api/research/stream/{sessionId}", sessionId).param("token", "garbage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void streamWithValidToken_opensSseAndEventsFlow() throws Exception {
        UUID sessionId = UUID.randomUUID();
        String token = jwtService.issue("alice", "USER");

        MvcResult mvcResult = mockMvc.perform(get("/api/research/stream/{sessionId}", sessionId)
                        .param("token", token))
                .andReturn();

        assertThat(mvcResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(mvcResult.getResponse().getContentType()).contains("text/event-stream");

        // The controller registered the emitter for this session; emit an event, then complete
        // the emitter so the async cycle ends and the mock response flushes.
        streamingService.sendProgress(sessionId, "integration test progress");
        streamingService.removeStream(sessionId);
        mvcResult.getAsyncResult(5_000);

        String body = mvcResult.getResponse().getContentAsString();
        assertThat(body).contains("event:PROGRESS");
        assertThat(body).contains("integration test progress");
    }
}
