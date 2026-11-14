package com.firstrow.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * One shared secret key both signs tokens (encoder, at login) and verifies them (decoder, on every request).
 * HS256 = HMAC with SHA-256: simple and right for a single backend that both issues and checks tokens.
 * (With many services you'd use RS256: a private key signs, everyone else verifies with the public key.)
 */
@Configuration
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);
    private static final int MIN_SECRET_BYTES = 32; // HS256 needs a key of at least 256 bits
    private static final String DEV_SECRET_PREFIX = "local-dev-only";

    private final SecretKey key;
    private final String issuer;

    public JwtConfig(FirstRowProperties props) {
        byte[] secret = props.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes long");
        }
        if (props.jwt().secret().startsWith(DEV_SECRET_PREFIX)) {
            log.warn("Using the built-in development JWT secret. Set JWT_SECRET in any deployed environment!");
        }
        this.key = new SecretKeySpec(secret, "HmacSHA256");
        this.issuer = props.jwt().issuer();
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /** Rejects tokens with a bad signature, a different issuer, or an expired "exp" claim. */
    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
