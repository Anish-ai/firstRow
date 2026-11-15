package com.firstrow.booking;

import com.firstrow.booking.BookingDtos.BookedSeat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Booking SQL. Like holds, every state change is a conditional UPDATE so concurrent requests can't both win. */
@Repository
public class BookingRepository {

    /** A booking joined with the event/venue fields the API shows. */
    private static final String SELECT_BOOKING = """
            SELECT b.id, b.user_id, b.event_id, b.hold_id, b.total_paise, b.status, b.idempotency_key, b.created_at,
                   e.title AS event_title, e.starts_at AS event_starts_at, v.name AS venue_name
            FROM bookings b
            JOIN events e ON e.id = b.event_id
            JOIN venues v ON v.id = e.venue_id
            """;

    private final JdbcClient jdbc;

    public BookingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BookingRow> findByIdempotencyKey(long userId, String idempotencyKey) {
        return jdbc.sql(SELECT_BOOKING + " WHERE b.user_id = :userId AND b.idempotency_key = :key")
                .param("userId", userId)
                .param("key", idempotencyKey)
                .query(BookingRepository::mapBooking)
                .optional();
    }

    public Optional<BookingRow> findById(long bookingId, long userId) {
        return jdbc.sql(SELECT_BOOKING + " WHERE b.id = :id AND b.user_id = :userId")
                .param("id", bookingId)
                .param("userId", userId)
                .query(BookingRepository::mapBooking)
                .optional();
    }

    public List<BookingRow> findByUser(long userId) {
        return jdbc.sql(SELECT_BOOKING + " WHERE b.user_id = :userId ORDER BY b.created_at DESC, b.id DESC")
                .param("userId", userId)
                .query(BookingRepository::mapBooking)
                .list();
    }

    /**
     * ACTIVE -> CONVERTED, only if the hold is the user's, has not expired (DB clock) and its event is still
     * scheduled (an admin may have cancelled it). Locks the hold row, so two confirms of the same hold are
     * serialised: the second one sees CONVERTED.
     */
    public Optional<ConvertedHold> convertHold(UUID holdId, long userId) {
        return jdbc.sql("""
                        UPDATE holds h SET status = 'CONVERTED'
                        FROM events e
                        WHERE h.id = :holdId AND h.user_id = :userId AND h.status = 'ACTIVE' AND h.expires_at > now()
                          AND e.id = h.event_id AND e.status = 'SCHEDULED'
                        RETURNING h.event_id, h.seat_count
                        """)
                .param("holdId", holdId)
                .param("userId", userId)
                .query((rs, rowNum) -> new ConvertedHold(rs.getLong("event_id"), rs.getInt("seat_count")))
                .optional();
    }

    /** The hold's status for this user, whether its time is up, and whether its event was cancelled. */
    public Optional<HoldState> findHold(UUID holdId, long userId) {
        return jdbc.sql("""
                        SELECT h.status, h.expires_at <= now() AS expired, e.status = 'CANCELLED' AS event_cancelled
                        FROM holds h JOIN events e ON e.id = h.event_id
                        WHERE h.id = :holdId AND h.user_id = :userId
                        """)
                .param("holdId", holdId)
                .param("userId", userId)
                .query((rs, rowNum) -> new HoldState(rs.getString("status"), rs.getBoolean("expired"),
                        rs.getBoolean("event_cancelled")))
                .optional();
    }

    /** HELD -> BOOKED for every seat still held by this hold. Returns the seats booked. */
    public List<BookedSeat> bookHeldSeats(UUID holdId) {
        return jdbc.sql("""
                        UPDATE event_seats SET status = 'BOOKED', held_until = NULL, version = version + 1
                        WHERE hold_id = :holdId AND status = 'HELD'
                        RETURNING id, section, row_label, seat_number, price_tier, price_paise
                        """)
                .param("holdId", holdId)
                .query(BookingRepository::mapSeat)
                .list();
    }

    public long insertBooking(long userId, long eventId, UUID holdId, long totalPaise, String idempotencyKey) {
        return jdbc.sql("""
                        INSERT INTO bookings (user_id, event_id, hold_id, total_paise, status, idempotency_key)
                        VALUES (:userId, :eventId, :holdId, :totalPaise, 'CONFIRMED', :key)
                        RETURNING id
                        """)
                .param("userId", userId)
                .param("eventId", eventId)
                .param("holdId", holdId)
                .param("totalPaise", totalPaise)
                .param("key", idempotencyKey)
                .query(Long.class)
                .single();
    }

    public void insertBookingSeats(long bookingId, Collection<Long> seatIds) {
        jdbc.sql("""
                        INSERT INTO booking_seats (booking_id, event_seat_id, active)
                        SELECT :bookingId, id, TRUE FROM event_seats WHERE id IN (:seatIds)
                        """)
                .param("bookingId", bookingId)
                .param("seatIds", seatIds)
                .update();
    }

    /** Seats of many bookings in ONE query (avoids a query per booking on the "my bookings" page). */
    public Map<Long, List<BookedSeat>> findSeatsByBooking(Collection<Long> bookingIds) {
        if (bookingIds.isEmpty()) {
            return Map.of();
        }
        record Row(long bookingId, BookedSeat seat) {
        }
        return jdbc.sql("""
                        SELECT bs.booking_id, s.id, s.section, s.row_label, s.seat_number, s.price_tier, s.price_paise
                        FROM booking_seats bs
                        JOIN event_seats s ON s.id = bs.event_seat_id
                        WHERE bs.booking_id IN (:ids)
                        ORDER BY s.section, s.row_label, s.seat_number
                        """)
                .param("ids", bookingIds)
                .query((rs, rowNum) -> new Row(rs.getLong("booking_id"), mapSeat(rs, rowNum)))
                .list()
                .stream()
                .collect(Collectors.groupingBy(Row::bookingId, LinkedHashMap::new,
                        Collectors.mapping(Row::seat, Collectors.toList())));
    }

    /** CONFIRMED -> CANCELLED for the owner. Returns rows changed (0 or 1). */
    public int markCancelled(long bookingId, long userId) {
        return jdbc.sql("""
                        UPDATE bookings SET status = 'CANCELLED'
                        WHERE id = :id AND user_id = :userId AND status = 'CONFIRMED'
                        """)
                .param("id", bookingId)
                .param("userId", userId)
                .update();
    }

    /** Takes the booking out of the partial unique index, so its seats can be booked by someone else. */
    public void deactivateBookingSeats(long bookingId) {
        jdbc.sql("UPDATE booking_seats SET active = FALSE WHERE booking_id = :id")
                .param("id", bookingId)
                .update();
    }

    /** BOOKED -> AVAILABLE for this booking's seats. */
    public int freeBookedSeats(long bookingId, UUID holdId) {
        return jdbc.sql("""
                        UPDATE event_seats SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL,
                                               version = version + 1
                        WHERE id IN (SELECT event_seat_id FROM booking_seats WHERE booking_id = :id)
                          AND status = 'BOOKED' AND hold_id = :holdId
                        """)
                .param("id", bookingId)
                .param("holdId", holdId)
                .update();
    }

    private static BookingRow mapBooking(ResultSet rs, int rowNum) throws SQLException {
        return new BookingRow(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getLong("event_id"),
                rs.getObject("hold_id", UUID.class),
                rs.getLong("total_paise"),
                rs.getString("status"),
                rs.getString("idempotency_key"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getString("event_title"),
                rs.getObject("event_starts_at", OffsetDateTime.class).toInstant(),
                rs.getString("venue_name"));
    }

    private static BookedSeat mapSeat(ResultSet rs, int rowNum) throws SQLException {
        return new BookedSeat(
                rs.getLong("id"),
                rs.getString("section"),
                rs.getString("row_label"),
                rs.getInt("seat_number"),
                rs.getString("price_tier"),
                rs.getLong("price_paise"));
    }

    public record BookingRow(long id, long userId, long eventId, UUID holdId, long totalPaise, String status,
                             String idempotencyKey, Instant createdAt, String eventTitle, Instant eventStartsAt,
                             String venueName) {
    }

    public record ConvertedHold(long eventId, int seatCount) {
    }

    public record HoldState(String status, boolean expired, boolean eventCancelled) {
    }
}
