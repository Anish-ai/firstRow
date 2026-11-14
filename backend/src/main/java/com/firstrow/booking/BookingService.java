package com.firstrow.booking;

import com.firstrow.booking.BookingDtos.BookedSeat;
import com.firstrow.booking.BookingDtos.BookingView;
import com.firstrow.booking.BookingDtos.EventInfo;
import com.firstrow.booking.BookingRepository.BookingRow;
import com.firstrow.booking.BookingRepository.ConvertedHold;
import com.firstrow.booking.BookingRepository.HoldState;
import com.firstrow.common.ApiException;
import com.firstrow.observability.BookingMetrics;
import com.firstrow.payment.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Confirming and cancelling bookings.
 *
 * Idempotency: the client sends an Idempotency-Key (a UUID made once per checkout attempt). If the network
 * drops after we booked but before the client got the answer, the client retries with the SAME key and
 * gets the ORIGINAL booking back (200) instead of a second booking or an error.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookings;
    private final PaymentGateway paymentGateway;
    private final Clock clock;
    private final BookingMetrics metrics;

    public BookingService(BookingRepository bookings, PaymentGateway paymentGateway, Clock clock,
                          BookingMetrics metrics) {
        this.bookings = bookings;
        this.paymentGateway = paymentGateway;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** {@code replayed} is true when an earlier request with the same key already created this booking. */
    public record ConfirmResult(BookingView booking, boolean replayed) {
    }

    /**
     * One transaction: hold -> CONVERTED, seats -> BOOKED, mock payment, booking + booking_seats inserted.
     * Any failure rolls all of it back.
     */
    @Transactional
    public ConfirmResult confirm(long userId, UUID holdId, String idempotencyKey) {
        // 1. Seen this key before? Return what we did the first time.
        var earlier = bookings.findByIdempotencyKey(userId, idempotencyKey);
        if (earlier.isPresent()) {
            return replay(earlier.get(), holdId);
        }

        // 2. Claim the hold. If two requests with the same key race, the second one waits here on the
        //    hold row's lock; when the first commits, this UPDATE matches nothing (hold is CONVERTED)...
        var converted = bookings.convertHold(holdId, userId);
        if (converted.isEmpty()) {
            // ...and, because READ COMMITTED gives each statement a fresh snapshot, this lookup now
            // sees the first request's booking, so the retry also gets the original booking back.
            var raced = bookings.findByIdempotencyKey(userId, idempotencyKey);
            if (raced.isPresent()) {
                return replay(raced.get(), holdId);
            }
            throw holdCannotBeBooked(holdId, userId);
        }
        ConvertedHold hold = converted.get();

        // 3. Every seat of the hold must still be ours (see V3 migration for why we count).
        List<BookedSeat> seats = bookings.bookHeldSeats(holdId);
        if (seats.size() != hold.seatCount()) {
            throw ApiException.conflict("Your hold expired before payment. Please choose your seats again.");
        }
        long totalPaise = seats.stream().mapToLong(BookedSeat::pricePaise).sum();

        // 4. Mock payment (always succeeds), then record the booking.
        paymentGateway.charge(userId, totalPaise, idempotencyKey);
        long bookingId;
        try {
            bookingId = bookings.insertBooking(userId, hold.eventId(), holdId, totalPaise, idempotencyKey);
        } catch (DuplicateKeyException e) {
            // Same key used concurrently for a DIFFERENT hold: the UNIQUE (user_id, idempotency_key) constraint stops it.
            throw ApiException.conflict("Another request with this Idempotency-Key is being processed");
        }
        bookings.insertBookingSeats(bookingId, seats.stream().map(BookedSeat::id).toList());

        metrics.bookingConfirmed();
        log.info("booking.confirmed bookingId={} userId={} eventId={} holdId={} seats={} totalPaise={}",
                bookingId, userId, hold.eventId(), holdId, seats.size(), totalPaise);
        return new ConfirmResult(toView(load(bookingId, userId), seats), false);
    }

    @Transactional(readOnly = true)
    public List<BookingView> myBookings(long userId) {
        List<BookingRow> rows = bookings.findByUser(userId);
        Map<Long, List<BookedSeat>> seats = bookings.findSeatsByBooking(rows.stream().map(BookingRow::id).toList());
        return rows.stream().map(row -> toView(row, seats.getOrDefault(row.id(), List.of()))).toList();
    }

    /** Cancelling is idempotent: cancelling an already-cancelled booking just returns it. */
    @Transactional
    public BookingView cancel(long userId, long bookingId) {
        BookingRow booking = load(bookingId, userId);
        if (booking.status().equals("CONFIRMED")) {
            if (!booking.eventStartsAt().isAfter(clock.instant())) {
                throw ApiException.conflict("This event has already started, so the booking can't be cancelled");
            }
            if (bookings.markCancelled(bookingId, userId) == 1) {
                bookings.deactivateBookingSeats(bookingId);
                int seatsFreed = bookings.freeBookedSeats(bookingId, booking.holdId());
                metrics.bookingCancelled();
                log.info("booking.cancelled bookingId={} userId={} eventId={} seatsFreed={}",
                        bookingId, userId, booking.eventId(), seatsFreed);
            }
            booking = load(bookingId, userId);
        }
        return toView(booking, bookings.findSeatsByBooking(List.of(bookingId)).getOrDefault(bookingId, List.of()));
    }

    private ConfirmResult replay(BookingRow booking, UUID holdId) {
        if (!booking.holdId().equals(holdId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "This Idempotency-Key was already used for a different hold. Use a new key for a new booking.");
        }
        log.info("booking.idempotent_replay bookingId={} userId={}", booking.id(), booking.userId());
        List<BookedSeat> seats = bookings.findSeatsByBooking(List.of(booking.id())).getOrDefault(booking.id(), List.of());
        return new ConfirmResult(toView(booking, seats), true);
    }

    private ApiException holdCannotBeBooked(UUID holdId, long userId) {
        HoldState state = bookings.findHold(holdId, userId)
                .orElseThrow(() -> ApiException.notFound("Hold " + holdId + " not found"));
        return switch (state.status()) {
            case "CONVERTED" -> ApiException.conflict("This hold has already been booked");
            case "RELEASED" -> ApiException.conflict("This hold was released. Please choose your seats again.");
            default -> ApiException.conflict("Your hold has expired. Please choose your seats again."); // EXPIRED, or ACTIVE past its time
        };
    }

    private BookingRow load(long bookingId, long userId) {
        return bookings.findById(bookingId, userId)
                .orElseThrow(() -> ApiException.notFound("Booking " + bookingId + " not found"));
    }

    private static BookingView toView(BookingRow row, List<BookedSeat> seats) {
        return new BookingView(row.id(), row.status(), row.totalPaise(), row.createdAt(), row.holdId(),
                new EventInfo(row.eventId(), row.eventTitle(), row.eventStartsAt(), row.venueName()), seats);
    }
}
