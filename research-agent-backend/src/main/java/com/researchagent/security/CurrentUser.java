package com.researchagent.security;

/**
 * Authenticated principal carried in the {@link org.springframework.security.core.context.SecurityContext}.
 *
 * @param username the token's {@code sub} claim (stored lowercase)
 * @param role     the token's {@code role} claim (e.g. {@code USER})
 */
public record CurrentUser(String username, String role) {
}
