package com.firstrow.auth;

import com.firstrow.auth.AuthDtos.AuthResponse;
import com.firstrow.auth.AuthDtos.LoginRequest;
import com.firstrow.auth.AuthDtos.SignupRequest;
import com.firstrow.common.ApiException;
import com.firstrow.user.Role;
import com.firstrow.user.User;
import com.firstrow.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    /** BCrypt ignores everything after 72 bytes, so longer passwords would be silently truncated. */
    static final int MAX_PASSWORD_BYTES = 72;
    static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final Clock clock;
    /** Used to spend the same BCrypt time when the email doesn't exist (see login). */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokenService,
                       Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-constant-time-login");
    }

    @Transactional
    public AuthResponse signup(SignupRequest request) {
        String email = normalizeEmail(request.email());
        if (tooLong(request.password())) {
            throw ApiException.badRequest("Password must be at most " + MAX_PASSWORD_BYTES + " bytes");
        }
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("An account with this email already exists");
        }
        User user;
        try {
            user = users.saveAndFlush(new User(email, passwordEncoder.encode(request.password()), Role.USER,
                    clock.instant()));
        } catch (DataIntegrityViolationException e) {
            // Two signups with the same email at the same moment: both passed existsByEmail,
            // but the UNIQUE constraint on users.email lets only one insert win.
            throw ApiException.conflict("An account with this email already exists");
        }
        log.info("user.signup userId={}", user.getId());
        return AuthResponse.of(tokenService.issue(user), user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        Optional<User> found = users.findByEmail(normalizeEmail(request.email()));

        if (found.isEmpty() || tooLong(request.password())) {
            // Still run BCrypt so "unknown email" takes as long as "wrong password";
            // otherwise response timing would reveal which emails have accounts.
            passwordEncoder.matches("not-the-password", dummyHash);
            throw ApiException.unauthorized(INVALID_CREDENTIALS);
        }
        User user = found.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized(INVALID_CREDENTIALS);
        }
        log.info("user.login userId={}", user.getId());
        return AuthResponse.of(tokenService.issue(user), user);
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean tooLong(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
    }
}
