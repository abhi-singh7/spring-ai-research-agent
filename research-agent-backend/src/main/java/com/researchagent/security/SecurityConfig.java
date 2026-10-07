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
 *   <li>Only {@code /api/**} requires a valid Bearer JWT (populated by {@link JwtAuthFilter}).
 *       The SPA shell ({@code index.html}, JS/CSS bundles) and client-side routes are PUBLIC:
 *       the browser cannot send a Bearer header on page/asset loads, so protecting them would
 *       401-wall the entire UI including {@code /login}. The Angular app authenticates via
 *       {@code /api/auth/login} and attaches the token to API calls only; unauthenticated API
 *       calls get 401 and the app redirects to login.</li>
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
                        // Order matters — first match wins: the public API paths stay open,
                        // every OTHER /api/** endpoint demands a Bearer JWT.
                        .requestMatchers("/api/auth/**", "/api/health", "/api/research/stream/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // SPA shell + static assets are public (see class javadoc).
                        .anyRequest().permitAll())
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
