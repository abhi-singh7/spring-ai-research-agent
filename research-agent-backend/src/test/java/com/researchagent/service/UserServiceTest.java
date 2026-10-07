package com.researchagent.service;

import com.researchagent.model.entity.User;
import com.researchagent.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserService}: registration validation, duplicate detection,
 * BCrypt storage and credential verification.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, encoder);
    }

    @Test
    void register_success_storesLowercaseUsernameAndBcryptHash() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.save(org.mockito.ArgumentMatchers.any(User.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        User saved = userService.register("Alice", "supersecret1");

        assertThat(saved.getUsername()).isEqualTo("alice");
        assertThat(saved.getRole()).isEqualTo("USER");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getPasswordHash()).isNotEqualTo("supersecret1");
        assertThat(encoder.matches("supersecret1", saved.getPasswordHash())).isTrue();
    }

    @Test
    void register_duplicateUsername_throws() {
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> userService.register("ALICE", "supersecret1"))
                .isInstanceOf(DuplicateUsernameException.class);
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void register_weakPassword_throws() {
        assertThatThrownBy(() -> userService.register("alice", "short"))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("at least 8");
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void register_malformedUsername_throws() {
        assertThatThrownBy(() -> userService.register("ab", "supersecret1"))
                .isInstanceOf(InvalidRegistrationException.class);
        assertThatThrownBy(() -> userService.register("has space", "supersecret1"))
                .isInstanceOf(InvalidRegistrationException.class);
        assertThatThrownBy(() -> userService.register("a".repeat(33), "supersecret1"))
                .isInstanceOf(InvalidRegistrationException.class);
        assertThatThrownBy(() -> userService.register(null, "supersecret1"))
                .isInstanceOf(InvalidRegistrationException.class);
    }

    @Test
    void authenticate_validCredentials_returnsUser() {
        User existing = new User();
        existing.setUsername("alice");
        existing.setPasswordHash(encoder.encode("supersecret1"));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(existing));

        assertThat(userService.authenticate("Alice", "supersecret1")).isSameAs(existing);
    }

    @Test
    void authenticate_wrongPassword_returnsNull() {
        User existing = new User();
        existing.setUsername("alice");
        existing.setPasswordHash(encoder.encode("supersecret1"));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(existing));

        assertThat(userService.authenticate("alice", "wrongpassword")).isNull();
    }

    @Test
    void authenticate_unknownUser_returnsNull() {
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());

        assertThat(userService.authenticate("ghost", "supersecret1")).isNull();
    }
}
