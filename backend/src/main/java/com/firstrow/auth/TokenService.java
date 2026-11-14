package com.firstrow.auth;

import com.firstrow.config.FirstRowProperties;
import com.firstrow.user.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Issues signed JWTs. A JWT is three base64 parts: header.payload.signature.
 * The payload ("claims") says who the user is and when the token expires; the HMAC-SHA256 signature
 * proves the server issued it, so on later requests we can trust it without a database lookup.
 * Validation of incoming tokens is done by Spring Security (see SecurityConfig / JwtConfig).
 */
@Service
public class TokenService {

    /** Claim holding the user's role; SecurityConfig maps it to ROLE_USER / ROLE_ADMIN. */
    public static final String ROLE_CLAIM = "role";

    private final JwtEncoder encoder;
    private final FirstRowProperties.Jwt jwtProps;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, FirstRowProperties props, Clock clock) {
        this.encoder = encoder;
        this.jwtProps = props.jwt();
        this.clock = clock;
    }

    public IssuedToken issue(User user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(jwtProps.ttl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProps.issuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(String.valueOf(user.getId()))   // "sub" = user id
                .claim("email", user.getEmail())
                .claim(ROLE_CLAIM, user.getRole().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
