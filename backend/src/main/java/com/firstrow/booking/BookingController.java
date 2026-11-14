package com.firstrow.booking;

import com.firstrow.booking.BookingDtos.BookingView;
import com.firstrow.booking.BookingDtos.ConfirmBookingRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Bookings", description = "Confirm a hold (mock payment), list and cancel bookings")
@SecurityRequirement(name = "bearerAuth")
public class BookingController {

    /** Response header telling the client "this is the stored result of an earlier request with your key". */
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @Operation(summary = "Confirm a hold and pay (mock payment always succeeds)",
            description = "201 for a new booking. Retrying with the same Idempotency-Key returns the original "
                    + "booking with 200 and `Idempotent-Replayed: true` instead of booking twice.")
    public ResponseEntity<BookingView> confirm(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "A UUID generated once per checkout attempt")
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
            @Valid @RequestBody ConfirmBookingRequest request) {
        BookingService.ConfirmResult result = bookingService.confirm(userId(jwt), request.holdId(), idempotencyKey);
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(result.booking());
        }
        return ResponseEntity.created(URI.create("/api/v1/bookings/" + result.booking().id())).body(result.booking());
    }

    @GetMapping("/me")
    @Operation(summary = "My bookings, newest first")
    public List<BookingView> myBookings(@AuthenticationPrincipal Jwt jwt) {
        return bookingService.myBookings(userId(jwt));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a booking and free its seats (idempotent)")
    public BookingView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return bookingService.cancel(userId(jwt), id);
    }

    private static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
