package com.firstrow.hold;

import com.firstrow.common.TokenBucketRateLimiter;
import com.firstrow.config.FirstRowProperties;
import com.firstrow.hold.HoldDtos.CreateHoldRequest;
import com.firstrow.hold.HoldDtos.HoldResponse;
import com.firstrow.observability.BookingMetrics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Tag(name = "Holds", description = "Hold seats for 5 minutes before booking")
@SecurityRequirement(name = "bearerAuth")
public class HoldController {

    private final HoldService holdService;
    private final TokenBucketRateLimiter rateLimiter;
    private final BookingMetrics metrics;

    public HoldController(HoldService holdService, FirstRowProperties props, BookingMetrics metrics) {
        this.holdService = holdService;
        this.metrics = metrics;
        this.rateLimiter = new TokenBucketRateLimiter(props.holds().rateLimitPerMinute(), System::nanoTime);
    }

    @PostMapping("/api/v1/events/{eventId}/holds")
    @Operation(summary = "Hold up to 6 seats for 5 minutes",
            description = "All-or-nothing. 409 with `unavailableSeatIds` if any seat was taken; 429 if rate-limited.")
    public ResponseEntity<HoldResponse> create(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable long eventId,
                                               @Valid @RequestBody CreateHoldRequest request) {
        long userId = userId(jwt);
        rateLimiter.acquire(userId);
        HoldResponse hold = metrics.timeHoldCreation(() -> holdService.createHold(userId, eventId, request.seatIds()));
        return ResponseEntity.status(201).body(hold);
    }

    @DeleteMapping("/api/v1/holds/{holdId}")
    @Operation(summary = "Release a hold early (frees its seats)")
    public ResponseEntity<Void> release(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID holdId) {
        holdService.releaseHold(userId(jwt), holdId);
        return ResponseEntity.noContent().build();
    }

    @Scheduled(fixedRate = 300_000)
    void evictIdleRateLimitBuckets() {
        rateLimiter.evictFullBuckets();
    }

    /** The JWT's "sub" claim is the user id (see TokenService). */
    static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
