package com.firstrow.booking;

import com.firstrow.booking.BookingDtos.BookedSeat;
import com.firstrow.booking.BookingRepository.BookingRow;
import com.firstrow.booking.BookingRepository.ConvertedHold;
import com.firstrow.booking.BookingRepository.HoldState;
import com.firstrow.common.ApiException;
import com.firstrow.observability.BookingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.firstrow.payment.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Booking rules with the repository and payment mocked. Real SQL/concurrency: BookingIntegrationTest. */
class BookingServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    private static final long USER = 7L;
    private static final long EVENT = 3L;
    private static final UUID HOLD = UUID.randomUUID();
    private static final String KEY = "key-1";

    private BookingRepository bookings;
    private PaymentGateway payments;
    private BookingService service;

    @BeforeEach
    void setUp() {
        bookings = mock(BookingRepository.class);
        payments = mock(PaymentGateway.class);
        service = new BookingService(bookings, payments, Clock.fixed(NOW, ZoneOffset.UTC),
                new BookingMetrics(new SimpleMeterRegistry()));
        when(bookings.findSeatsByBooking(anyCollection())).thenReturn(Map.of());
    }

    @Test
    void confirmBooksAllSeatsChargesTheTotalAndRecordsTheBooking() {
        when(bookings.findByIdempotencyKey(USER, KEY)).thenReturn(Optional.empty());
        when(bookings.convertHold(HOLD, USER)).thenReturn(Optional.of(new ConvertedHold(EVENT, 2)));
        when(bookings.bookHeldSeats(HOLD)).thenReturn(List.of(seat(1, 75_000), seat(2, 45_000)));
        when(bookings.insertBooking(USER, EVENT, HOLD, 120_000, KEY)).thenReturn(99L);
        when(bookings.findById(99L, USER)).thenReturn(Optional.of(booking(99L, HOLD, "CONFIRMED", 120_000)));

        BookingService.ConfirmResult result = service.confirm(USER, HOLD, KEY);

        assertThat(result.replayed()).isFalse();
        assertThat(result.booking().id()).isEqualTo(99L);
        assertThat(result.booking().totalPaise()).isEqualTo(120_000);
        verify(payments).charge(USER, 120_000, KEY);
        verify(bookings).insertBookingSeats(99L, List.of(1L, 2L));
    }

    @Test
    void sameKeyAndSameHoldReturnsTheOriginalBookingWithoutDoingAnything() {
        when(bookings.findByIdempotencyKey(USER, KEY))
                .thenReturn(Optional.of(booking(99L, HOLD, "CONFIRMED", 120_000)));

        BookingService.ConfirmResult result = service.confirm(USER, HOLD, KEY);

        assertThat(result.replayed()).isTrue();
        assertThat(result.booking().id()).isEqualTo(99L);
        verify(bookings, never()).convertHold(any(), anyLong());
        verify(payments, never()).charge(anyLong(), anyLong(), anyString());
    }

    @Test
    void sameKeyForADifferentHoldIs422() {
        when(bookings.findByIdempotencyKey(USER, KEY))
                .thenReturn(Optional.of(booking(99L, UUID.randomUUID(), "CONFIRMED", 120_000)));

        assertStatus(() -> service.confirm(USER, HOLD, KEY), HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void concurrentDuplicateThatLosesTheHoldLockGetsTheWinnersBooking() {
        // First lookup: nothing yet. The hold is then found CONVERTED (the winner committed meanwhile),
        // and the second lookup sees the winner's booking.
        when(bookings.findByIdempotencyKey(USER, KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(booking(99L, HOLD, "CONFIRMED", 120_000)));
        when(bookings.convertHold(HOLD, USER)).thenReturn(Optional.empty());

        BookingService.ConfirmResult result = service.confirm(USER, HOLD, KEY);

        assertThat(result.replayed()).isTrue();
        assertThat(result.booking().id()).isEqualTo(99L);
        verify(payments, never()).charge(anyLong(), anyLong(), anyString());
    }

    @Test
    void expiredHoldIsConflict() {
        when(bookings.findByIdempotencyKey(USER, KEY)).thenReturn(Optional.empty());
        when(bookings.convertHold(HOLD, USER)).thenReturn(Optional.empty());
        when(bookings.findHold(HOLD, USER)).thenReturn(Optional.of(new HoldState("ACTIVE", true, false)));

        assertThatThrownBy(() -> service.confirm(USER, HOLD, KEY))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void alreadyBookedHoldWithANewKeyIsConflict() {
        when(bookings.findByIdempotencyKey(USER, "new-key")).thenReturn(Optional.empty());
        when(bookings.convertHold(HOLD, USER)).thenReturn(Optional.empty());
        when(bookings.findHold(HOLD, USER)).thenReturn(Optional.of(new HoldState("CONVERTED", false, false)));

        assertThatThrownBy(() -> service.confirm(USER, HOLD, "new-key"))
                .isInstanceOf(ApiException.class)
                .hasMessage("This hold has already been booked");
    }

    @Test
    void someoneElsesOrUnknownHoldIsNotFound() {
        when(bookings.findByIdempotencyKey(USER, KEY)).thenReturn(Optional.empty());
        when(bookings.convertHold(HOLD, USER)).thenReturn(Optional.empty());
        when(bookings.findHold(HOLD, USER)).thenReturn(Optional.empty());

        assertStatus(() -> service.confirm(USER, HOLD, KEY), HttpStatus.NOT_FOUND);
    }

    @Test
    void seatLostAtTheLastMomentFailsWithoutChargingOrBooking() {
        when(bookings.findByIdempotencyKey(USER, KEY)).thenReturn(Optional.empty());
        when(bookings.convertHold(HOLD, USER)).thenReturn(Optional.of(new ConvertedHold(EVENT, 2)));
        when(bookings.bookHeldSeats(HOLD)).thenReturn(List.of(seat(1, 75_000))); // only 1 of 2 still ours

        assertStatus(() -> service.confirm(USER, HOLD, KEY), HttpStatus.CONFLICT);
        verify(payments, never()).charge(anyLong(), anyLong(), anyString());
        verify(bookings, never()).insertBooking(anyLong(), anyLong(), any(), anyLong(), anyString());
    }

    @Test
    void sameKeyRacingForADifferentHoldHitsTheUniqueConstraintAndIsConflict() {
        when(bookings.findByIdempotencyKey(USER, KEY)).thenReturn(Optional.empty());
        when(bookings.convertHold(HOLD, USER)).thenReturn(Optional.of(new ConvertedHold(EVENT, 1)));
        when(bookings.bookHeldSeats(HOLD)).thenReturn(List.of(seat(1, 75_000)));
        when(bookings.insertBooking(anyLong(), anyLong(), any(), anyLong(), eq(KEY)))
                .thenThrow(new DuplicateKeyException("uq_booking_user_idempotency_key"));

        assertStatus(() -> service.confirm(USER, HOLD, KEY), HttpStatus.CONFLICT);
    }

    @Test
    void cancelFreesSeats() {
        when(bookings.findById(99L, USER))
                .thenReturn(Optional.of(booking(99L, HOLD, "CONFIRMED", 1)))
                .thenReturn(Optional.of(booking(99L, HOLD, "CANCELLED", 1)));
        when(bookings.markCancelled(99L, USER)).thenReturn(1);

        assertThat(service.cancel(USER, 99L).status()).isEqualTo("CANCELLED");
        verify(bookings).deactivateBookingSeats(99L);
        verify(bookings).freeBookedSeats(99L, HOLD);
    }

    @Test
    void cancellingTwiceIsHarmless() {
        when(bookings.findById(99L, USER)).thenReturn(Optional.of(booking(99L, HOLD, "CANCELLED", 1)));

        assertThat(service.cancel(USER, 99L).status()).isEqualTo("CANCELLED");
        verify(bookings, never()).markCancelled(anyLong(), anyLong());
    }

    @Test
    void cannotCancelAfterTheEventStarted() {
        BookingRow started = new BookingRow(99L, USER, EVENT, HOLD, 1, "CONFIRMED", KEY, NOW,
                "Old Match", NOW.minus(Duration.ofHours(1)), "Riverside Arena");
        when(bookings.findById(99L, USER)).thenReturn(Optional.of(started));

        assertStatus(() -> service.cancel(USER, 99L), HttpStatus.CONFLICT);
        verify(bookings, never()).markCancelled(anyLong(), anyLong());
    }

    @Test
    void cancellingSomeoneElsesBookingIsNotFound() {
        when(bookings.findById(99L, USER)).thenReturn(Optional.empty());

        assertStatus(() -> service.cancel(USER, 99L), HttpStatus.NOT_FOUND);
    }

    private static void assertStatus(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    private static BookingRow booking(long id, UUID holdId, String status, long totalPaise) {
        return new BookingRow(id, USER, EVENT, holdId, totalPaise, status, KEY, NOW,
                "Starfall — Premiere Night", NOW.plus(Duration.ofDays(10)), "Aurora Grand Cinema");
    }

    private static BookedSeat seat(long id, long pricePaise) {
        return new BookedSeat(id, "A", "A", (int) id, "GOLD", pricePaise);
    }
}
