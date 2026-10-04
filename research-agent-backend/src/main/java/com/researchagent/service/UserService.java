package com.researchagent.service;

import com.researchagent.model.entity.User;
import com.researchagent.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * User registration and credential verification.
 *
 * <p>Rules: username is 3–32 characters of {@code [a-zA-Z0-9_]} and unique case-insensitively
 * (stored lowercase); password is at least 8 characters and stored only as a BCrypt hash.</p>
 */
@Service
@Slf4j
public class UserService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,32}$");
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Register a new user.
     *
     * @throws InvalidRegistrationException if the username or password violates a rule
     * @throws DuplicateUsernameException   if the username is already taken (case-insensitive)
     */
    public User register(String username, String password) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new InvalidRegistrationException("username must be 3-32 characters of [a-zA-Z0-9_]");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new InvalidRegistrationException("password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }

        String normalizedUsername = username.toLowerCase(Locale.ROOT);
        if (userRepository.existsByUsername(normalizedUsername)) {
            throw new DuplicateUsernameException("username is already taken");
        }

        User user = new User();
        user.setUsername(normalizedUsername);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(User.DEFAULT_ROLE);
        user.setCreatedAt(LocalDateTime.now());

        User saved = userRepository.save(user);
        log.info("Registered new user {}", saved.getUsername());
        return saved;
    }

    /**
     * Verify credentials.
     *
     * @return the user on valid credentials, {@code null} otherwise (unknown username or wrong password)
     */
    public User authenticate(String username, String password) {
        if (username == null || password == null) {
            return null;
        }
        return userRepository.findByUsername(username.toLowerCase(Locale.ROOT))
                .filter(user -> passwordEncoder.matches(password, user.getPasswordHash()))
                .orElse(null);
    }

    public Optional<User> findByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        return userRepository.findByUsername(username.toLowerCase(Locale.ROOT));
    }
}
