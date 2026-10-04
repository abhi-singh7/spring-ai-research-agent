package com.researchagent.model.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Application user document (collection {@code user}).
 *
 * <p>Username is stored in lowercase; the unique index on it makes registration
 * case-insensitive. Passwords are stored only as BCrypt hashes — never plaintext.</p>
 */
@Data
@Document(collection = "user")
public class User {

    public static final String DEFAULT_ROLE = "USER";

    @Id
    private UUID id = UUID.randomUUID();

    @Indexed(unique = true)
    private String username;

    private String passwordHash;

    private String role = DEFAULT_ROLE;

    private LocalDateTime createdAt;
}
