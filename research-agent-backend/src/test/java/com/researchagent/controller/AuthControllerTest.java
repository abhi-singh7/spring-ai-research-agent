package com.researchagent.controller;

import com.researchagent.model.entity.User;
import com.researchagent.security.CurrentUser;
import com.researchagent.security.JwtService;
import com.researchagent.security.SecurityConfig;
import com.researchagent.service.DuplicateUsernameException;
import com.researchagent.service.InvalidRegistrationException;
import com.researchagent.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller tests for {@link AuthController} using a REAL {@link JwtService} (minted tokens are
 * validated for real) and the real {@link SecurityConfig} filter chain. The user repository is
 * replaced by a mocked {@link UserService}.
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    static final String TEST_SECRET = "auth-controller-test-secret-32-bytes!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private UserService userService;

    @TestConfiguration
    static class TestJwtConfig {
        @Bean
        JwtService jwtService() {
            return new JwtService(TEST_SECRET, 3600, "research-agent");
        }
    }


    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setRole("USER");
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }

    @Test
    void register_success_returns201WithValidToken() throws Exception {
        when(userService.register("alice", "supersecret1")).thenReturn(user("alice"));

        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"supersecret1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.token").exists())
                .andReturn().getResponse().getContentAsString();

        // The issued token must be a real, valid JWT for the new user
        String token = extractToken(body);
        CurrentUser validated = jwtService.validate(token);
        assertThat(validated.username()).isEqualTo("alice");
        assertThat(validated.role()).isEqualTo("USER");
    }

    @Test
    void register_duplicateUsername_returns409() throws Exception {
        when(userService.register("alice", "supersecret1"))
                .thenThrow(new DuplicateUsernameException("username is already taken"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"supersecret1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void register_weakPassword_returns400WithRule() throws Exception {
        when(userService.register("alice", "short"))
                .thenThrow(new InvalidRegistrationException("password must be at least 8 characters"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("password must be at least 8 characters"));
    }

    @Test
    void register_malformedUsername_returns400() throws Exception {
        when(userService.register("ab", "supersecret1"))
                .thenThrow(new InvalidRegistrationException("username must be 3-32 characters of [a-zA-Z0-9_]"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ab\",\"password\":\"supersecret1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void login_success_returns200WithValidToken() throws Exception {
        when(userService.authenticate("alice", "supersecret1")).thenReturn(user("alice"));

        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"supersecret1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.token").exists())
                .andReturn().getResponse().getContentAsString();

        CurrentUser validated = jwtService.validate(extractToken(body));
        assertThat(validated.username()).isEqualTo("alice");
    }

    @Test
    void login_wrongPassword_returns401Json() throws Exception {
        when(userService.authenticate("alice", "wrongpassword")).thenReturn(null);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrongpassword\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void me_withValidBearerToken_returns200WithIdentity() throws Exception {
        when(userService.findByUsername("alice")).thenReturn(java.util.Optional.of(user("alice")));

        // Real minted token; the imported SecurityConfig chain runs in MockMvc and authenticates it
        String token = jwtService.issue("alice", "USER");

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void me_withoutToken_returns401Json() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void me_withInvalidToken_returns401Json() throws Exception {
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer garbage.token.here"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    private static String extractToken(String jsonBody) {
        // Body shape: {"token":"...","username":"..."} — avoid pulling in a JSON lib just for tests
        int start = jsonBody.indexOf("\"token\":\"") + "\"token\":\"".length();
        int end = jsonBody.indexOf('"', start);
        return jsonBody.substring(start, end);
    }
}
