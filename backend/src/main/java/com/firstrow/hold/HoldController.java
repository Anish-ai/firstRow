package com.firstrow.hold;

import com.firstrow.common.TokenBucketRateLimiter;
import com.firstrow.config.FirstRowProperties;
import com.firstrow.hold.HoldDtos.CreateHoldRequest;
import com.firstrow.hold.HoldDtos.HoldResponse;
import com.firstrow.hold.HoldDtos.UpdateHoldSeatsRequest;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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
            description = "All-or-nothing. 409 with `unavailableSeatIds` if any seat was taken; 409 if you already "
                    + "have a hold for this event or would exceed 6 seats per event (booked + held); 429 if rate-limited.")
    public ResponseEntity<HoldResponse> create(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable long eventId,
                                               @Valid @RequestBody CreateHoldRequest request) {
        long userId = userId(jwt);
        rateLimiter.acquire(userId);
        HoldResponse hold = metrics.timeHoldCreation(() -> holdService.createHold(userId, eventId, request.seatIds()));
        return ResponseEntity.status(201).body(hold);
    }

    @PutMapping("/api/v1/holds/{holdId}/seats")
    @Operation(summary = "Change the seats of your hold",
            description = "Replaces the hold's seats (max 6). Seats you drop are freed; new seats are claimed "
                    + "all-or-nothing (409 with `unavailableSeatIds` leaves the hold unchanged). The expiry time "
                    + "does NOT reset. An empty list releases the hold (204).")
    public ResponseEntity<HoldResponse> updateSeats(@AuthenticationPrincipal Jwt jwt,
                                                    @PathVariable UUID holdId,
                                                    @Valid @RequestBody UpdateHoldSeatsRequest request) {
        long userId = userId(jwt);
        rateLimiter.acquire(userId);
        if (request.seatIds().isEmpty()) {
            holdService.releaseHold(userId, holdId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(holdService.updateHoldSeats(userId, holdId, request.seatIds()));
    }

    @GetMapping("/api/v1/holds/me")
    @Operation(summary = "Your active hold for an event", description = "200 with the hold, or 204 if you have none.")
    public ResponseEntity<HoldResponse> myHold(@AuthenticationPrincipal Jwt jwt, @RequestParam long eventId) {
        return holdService.findMyHold(userId(jwt), eventId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/api/v1/holds/{holdId}")
    @Operation(summary = "One of your holds, while it is active", description = "404 once it expired, was released or booked.")
    public HoldResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID holdId) {
        return holdService.getActiveHold(userId(jwt), holdId);
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
