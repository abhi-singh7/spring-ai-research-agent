package com.researchagent.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Stateless JWT security chain (openspec/changes/add-jwt-authentication).
 *
 * <ul>
 *   <li>{@code /api/auth/**} (register/login/me), {@code /api/health} and
 *       {@code /api/research/stream/**} are permitted through the filter chain — register/login
 *       need no prior auth, the health probe is an unauthenticated liveness check for deploy
 *       gates, and the stream endpoint self-validates its {@code ?token=} query parameter
 *       before opening the SSE connection.</li>
 *   <li>Every other request requires a valid Bearer JWT (populated by {@link JwtAuthFilter}).</li>
 *   <li>CSRF is disabled: no auth-carrying cookies exist (tokens ride in headers / one query param),
 *       so the classic CSRF surface does not. Re-enable if auth ever moves to httpOnly cookies.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * NOTE: {@link JwtAuthFilter} is deliberately NOT exposed as a {@code @Bean}. Any {@code Filter}
     * bean is auto-registered by Spring Boot as a servlet filter, which would run it OUTSIDE the
     * security chain as well — its context write would then be wiped by the chain's
     * {@code SecurityContextHolderFilter} and the in-chain instance suppressed by
     * {@code OncePerRequestFilter}, leaving every request anonymous. It is instantiated inline so
     * only the chain runs it.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/health", "/api/research/stream/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, e) ->
                                writeJsonError(response, HttpStatus.UNAUTHORIZED, "Authentication required"))
                        .accessDeniedHandler((request, response, e) ->
                                writeJsonError(response, HttpStatus.FORBIDDEN, "Access is denied")));
        http.addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Uniform JSON error shape for auth failures: {@code {"error": "..."}}.
     */
    private static void writeJsonError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }
}
