package com.firstrow.auth;

import com.firstrow.config.FirstRowProperties;
import com.firstrow.config.JwtConfig;
import com.firstrow.user.Role;
import com.firstrow.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses the real encoder/decoder from JwtConfig, without starting Spring. */
class TokenServiceTest {

    private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long";

    @Test
    void issuedTokenCarriesUserIdRoleAndExpiry() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JwtConfig config = new JwtConfig(props(SECRET, Duration.ofHours(1)));
        TokenService tokens = new TokenService(config.jwtEncoder(), props(SECRET, Duration.ofHours(1)),
                Clock.fixed(now, ZoneOffset.UTC));

        TokenService.IssuedToken issued = tokens.issue(admin(42L));
        Jwt jwt = config.jwtDecoder().decode(issued.value());

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
        assertThat(jwt.getClaimAsString("email")).isEqualTo("boss@example.com");
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plus(Duration.ofHours(1))).isEqualTo(issued.expiresAt());
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtConfig ours = new JwtConfig(props(SECRET, Duration.ofHours(1)));
        JwtConfig attacker = new JwtConfig(props("a-different-secret-also-32-bytes-long!!", Duration.ofHours(1)));
        TokenService attackerTokens = new TokenService(attacker.jwtEncoder(),
                props("a-different-secret-also-32-bytes-long!!", Duration.ofHours(1)), Clock.systemUTC());

        String forged = attackerTokens.issue(admin(1L)).value();

        assertThatThrownBy(() -> ours.jwtDecoder().decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        JwtConfig config = new JwtConfig(props(SECRET, Duration.ofMinutes(5)));
        // Issued 2 hours ago with a 5 minute lifetime (well past the decoder's 60s clock-skew allowance).
        Clock twoHoursAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        TokenService tokens = new TokenService(config.jwtEncoder(), props(SECRET, Duration.ofMinutes(5)), twoHoursAgo);

        String expired = tokens.issue(admin(1L)).value();

        assertThatThrownBy(() -> config.jwtDecoder().decode(expired))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void secretShorterThan32BytesFailsAtStartup() {
        assertThatThrownBy(() -> new JwtConfig(props("too-short", Duration.ofHours(1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    private static FirstRowProperties props(String secret, Duration ttl) {
        return new FirstRowProperties(
                new FirstRowProperties.Jwt(secret, ttl, "firstrow"),
                new FirstRowProperties.Cors(List.of("http://localhost:5173")),
                new FirstRowProperties.Admin(null, null),
                new FirstRowProperties.Holds(Duration.ofMinutes(5), 6, 10));
    }

    private static User admin(long id) {
        User user = new User("boss@example.com", "hash", Role.ADMIN, Instant.now());
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
