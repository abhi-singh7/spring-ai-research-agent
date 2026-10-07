package com.researchagent.controller;

import com.researchagent.model.entity.User;
import com.researchagent.security.CurrentUser;
import com.researchagent.security.JwtService;
import com.researchagent.service.DuplicateUsernameException;
import com.researchagent.service.InvalidRegistrationException;
import com.researchagent.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Authentication endpoints (openspec/changes/add-jwt-authentication):
 *
 * <pre>
 * POST /api/auth/register  {username, password}  → 201 {token, username} | 409 duplicate | 400 validation
 * POST /api/auth/login     {username, password}  → 200 {token, username} | 401 {error}
 * GET  /api/auth/me        Bearer JWT            → 200 {username, role, createdAt} | 401 {error}
 * </pre>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record AuthRequest(String username, String password) {
    }

    public record TokenResponse(String token, String username) {
    }

    public record MeResponse(String username, String role, LocalDateTime createdAt) {
    }

    private final UserService userService;
    private final JwtService jwtService;

    public AuthController(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    /**
     * Register a new user and auto-login: returns the persisted user's JWT immediately.
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody AuthRequest request) {
        try {
            User user = userService.register(request.username(), request.password());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new TokenResponse(jwtService.issue(user.getUsername(), user.getRole()), user.getUsername()));
        } catch (DuplicateUsernameException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (InvalidRegistrationException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Exchange valid credentials for a JWT.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest request) {
        User user = userService.authenticate(request.username(), request.password());
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password"));
        }
        return ResponseEntity.ok(new TokenResponse(jwtService.issue(user.getUsername(), user.getRole()), user.getUsername()));
    }

    /**
     * Identity of the token's subject. The /api/auth/** path is permitted through the filter chain,
     * so this endpoint enforces authentication itself: a missing/invalid Bearer token yields 401.
     */
    @GetMapping("/me")
    public ResponseEntity<?> me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof CurrentUser currentUser)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));
        }
        User user = userService.findByUsername(currentUser.username()).orElse(null);
        if (user == null) {
            // Token subject no longer exists — treat as unauthenticated.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unknown user for token"));
        }
        return ResponseEntity.ok(new MeResponse(user.getUsername(), user.getRole(), user.getCreatedAt()));
    }
}
