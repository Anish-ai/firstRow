package com.firstrow.booking;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BookingDtos {

    private BookingDtos() {
    }

    public record ConfirmBookingRequest(@NotNull UUID holdId) {
    }

    public record BookingView(long id,
                              String status,
                              long totalPaise,
                              Instant createdAt,
                              UUID holdId,
                              EventInfo event,
                              List<BookedSeat> seats) {
    }

    public record EventInfo(long id, String title, Instant startsAt, String venueName) {
    }

    public record BookedSeat(long id, String section, String row, int number, String priceTier, long pricePaise) {
    }
}
