package com.firstrow.hold;

import com.firstrow.event.SeatView;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class HoldDtos {

    private HoldDtos() {
    }

    /** The "max 6 seats" and "no duplicates" rules are enforced in HoldService (and unit tested there). */
    public record CreateHoldRequest(@NotEmpty List<@NotNull Long> seatIds) {
    }

    /** New full set of seats for an existing hold (max 6). An empty list releases the hold. */
    public record UpdateHoldSeatsRequest(@NotNull List<@NotNull Long> seatIds) {
    }

    public record HoldResponse(UUID holdId, long eventId, Instant expiresAt, List<SeatView> seats, long totalPaise) {
    }
}
