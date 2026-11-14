package com.firstrow.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Typed view of the {@code firstrow.*} settings in application.yml.
 * Validated at startup, so a missing or broken setting stops the app immediately
 * instead of failing later on the first request.
 */
@Validated
@ConfigurationProperties(prefix = "firstrow")
public record FirstRowProperties(@Valid @NotNull Jwt jwt,
                                 @Valid @NotNull Cors cors,
                                 @Valid @NotNull Admin admin,
                                 @Valid @NotNull Holds holds) {

    /**
     * @param ttl                how long a hold keeps its seats (5 minutes)
     * @param maxSeats           most seats one hold may contain
     * @param rateLimitPerMinute hold requests allowed per user per minute
     */
    public record Holds(@NotNull Duration ttl, @Min(1) int maxSeats, @Min(1) int rateLimitPerMinute) {
    }

    public record Jwt(@NotBlank String secret, @NotNull Duration ttl, @NotBlank String issuer) {
    }

    public record Cors(@NotNull List<String> allowedOrigins) {
    }

    /** Optional bootstrap admin account; both values empty means "don't create one". */
    public record Admin(String email, String password) {
    }
}
