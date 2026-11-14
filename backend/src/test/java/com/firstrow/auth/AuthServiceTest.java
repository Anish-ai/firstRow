package com.firstrow.auth;

import com.firstrow.auth.AuthDtos.AuthResponse;
import com.firstrow.auth.AuthDtos.LoginRequest;
import com.firstrow.auth.AuthDtos.SignupRequest;
import com.firstrow.common.ApiException;
import com.firstrow.user.Role;
import com.firstrow.user.User;
import com.firstrow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests: the repository and token service are Mockito mocks, so no database or Spring is involved. */
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

    private UserRepository users;
    private TokenService tokenService;
    // Real BCrypt with the minimum cost (4) so the tests stay fast.
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private AuthService authService;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        tokenService = mock(TokenService.class);
        when(tokenService.issue(any())).thenReturn(new TokenService.IssuedToken("jwt-token", NOW.plusSeconds(3600)));
        authService = new AuthService(users, passwordEncoder, tokenService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void signupStoresNormalizedEmailAndHashedPassword() {
        when(users.saveAndFlush(any())).thenAnswer(inv -> withId(inv.getArgument(0), 7L));

        AuthResponse response = authService.signup(new SignupRequest("  Fan@Example.COM ", "correct-horse"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("fan@example.com");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.USER);
        assertThat(saved.getValue().getPasswordHash()).isNotEqualTo("correct-horse");
        assertThat(passwordEncoder.matches("correct-horse", saved.getValue().getPasswordHash())).isTrue();
        assertThat(response.accessToken()).isEqualTo("jwt-token");
        assertThat(response.user().id()).isEqualTo(7L);
    }

    @Test
    void signupWithExistingEmailIsConflict() {
        when(users.existsByEmail("fan@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(new SignupRequest("fan@example.com", "correct-horse")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void concurrentSignupLosingTheUniqueConstraintRaceIsConflict() {
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("users_email_key"));

        assertThatThrownBy(() -> authService.signup(new SignupRequest("fan@example.com", "correct-horse")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void signupRejectsPasswordLongerThan72Bytes() {
        String seventyThreeBytes = "é".repeat(36) + "a"; // 'é' is 2 bytes in UTF-8, so 36 chars = 72 bytes + 1

        assertThatThrownBy(() -> authService.signup(new SignupRequest("fan@example.com", seventyThreeBytes)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void loginWithCorrectPasswordIssuesToken() {
        User user = withId(new User("fan@example.com", passwordEncoder.encode("correct-horse"), Role.USER, NOW), 7L);
        when(users.findByEmail("fan@example.com")).thenReturn(Optional.of(user));

        AuthResponse response = authService.login(new LoginRequest("FAN@example.com", "correct-horse"));

        assertThat(response.accessToken()).isEqualTo("jwt-token");
        verify(tokenService).issue(user);
    }

    @Test
    void loginWithWrongPasswordAndUnknownEmailGiveTheSameError() {
        User user = withId(new User("fan@example.com", passwordEncoder.encode("correct-horse"), Role.USER, NOW), 7L);
        when(users.findByEmail("fan@example.com")).thenReturn(Optional.of(user));
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("fan@example.com", "wrong-password")))
                .isInstanceOf(ApiException.class)
                .hasMessage(AuthService.INVALID_CREDENTIALS);
        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "correct-horse")))
                .isInstanceOf(ApiException.class)
                .hasMessage(AuthService.INVALID_CREDENTIALS);
        verify(tokenService, never()).issue(any());
    }

    private static User withId(User user, long id) {
        ReflectionTestUtils.setField(user, "id", id); // the DB would assign this on insert
        return user;
    }
}
