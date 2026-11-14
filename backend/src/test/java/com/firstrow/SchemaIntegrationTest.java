package com.firstrow;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks that the Flyway migrations produce the schema and seed data we expect,
 * and that the database-level safety nets actually reject bad data.
 * Each test runs in a transaction that is rolled back, so tests don't affect each other.
 */
@Transactional
class SchemaIntegrationTest extends AbstractIntegrationTest {

    @Test
    void seedDataCreatesFourEventsWithAllSeatsAvailable() {
        assertThat(count("SELECT count(*) FROM events")).isEqualTo(4);
        // 2 premieres x (6 rows x 14 seats) + 2 matches x (2 sections x 10 rows x 20 seats)
        assertThat(count("SELECT count(*) FROM event_seats")).isEqualTo(2 * 84 + 2 * 400);
        assertThat(count("SELECT count(*) FROM event_seats WHERE status <> 'AVAILABLE'")).isZero();
    }

    @Test
    void seedEventsAreInTheFuture() {
        assertThat(count("SELECT count(*) FROM events WHERE starts_at <= now()")).isZero();
    }

    @Test
    void sameSeatCannotExistTwiceForOneEvent() {
        long eventId = anyEventId();
        String insert = """
                INSERT INTO event_seats (event_id, section, row_label, seat_number, price_tier, price_paise)
                VALUES (?, 'Z', 'A', 1, 'GOLD', 45000)
                """;
        jdbc.update(insert, eventId);

        assertThatThrownBy(() -> jdbc.update(insert, eventId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_event_seat");
    }

    @Test
    void heldSeatMustRecordItsHold() {
        long seatId = anySeatId();

        assertThatThrownBy(() -> jdbc.update("UPDATE event_seats SET status = 'HELD' WHERE id = ?", seatId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_hold_fields");
    }

    @Test
    void seatCannotBeInTwoActiveBookings() {
        long seatId = anySeatId();
        long firstBooking = insertBooking("key-1");
        long secondBooking = insertBooking("key-2");

        jdbc.update("INSERT INTO booking_seats (booking_id, event_seat_id) VALUES (?, ?)", firstBooking, seatId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO booking_seats (booking_id, event_seat_id) VALUES (?, ?)", secondBooking, seatId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_booking_seats_active_seat");
    }

    @Test
    void seatCanBeRebookedAfterTheFirstBookingIsCancelled() {
        long seatId = anySeatId();
        long cancelledBooking = insertBooking("key-1");
        long newBooking = insertBooking("key-2");

        jdbc.update("INSERT INTO booking_seats (booking_id, event_seat_id, active) VALUES (?, ?, false)",
                cancelledBooking, seatId);
        jdbc.update("INSERT INTO booking_seats (booking_id, event_seat_id) VALUES (?, ?)", newBooking, seatId);

        assertThat(count("SELECT count(*) FROM booking_seats WHERE event_seat_id = " + seatId)).isEqualTo(2);
    }

    @Test
    void sameIdempotencyKeyCannotBeUsedTwiceBySameUser() {
        long userId = insertUser("idem@example.com");
        insertBooking(userId, "same-key");

        assertThatThrownBy(() -> insertBooking(userId, "same-key"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_booking_user_idempotency_key");
    }

    // ---- helpers ---------------------------------------------------------

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private long anyEventId() {
        return jdbc.queryForObject("SELECT min(id) FROM events", Long.class);
    }

    private long anySeatId() {
        return jdbc.queryForObject("SELECT min(id) FROM event_seats", Long.class);
    }

    private long insertUser(String email) {
        return jdbc.queryForObject(
                "INSERT INTO users (email, password_hash) VALUES (?, 'not-a-real-hash') RETURNING id",
                Long.class, email);
    }

    private long insertBooking(String idempotencyKey) {
        long userId = insertUser(idempotencyKey + "@example.com");
        return insertBooking(userId, idempotencyKey);
    }

    private long insertBooking(long userId, String idempotencyKey) {
        long eventId = anyEventId();
        UUID holdId = UUID.randomUUID();
        jdbc.update("INSERT INTO holds (id, user_id, event_id, expires_at, status) "
                + "VALUES (?, ?, ?, now() + interval '5 minutes', 'CONVERTED')", holdId, userId, eventId);
        return jdbc.queryForObject("""
                INSERT INTO bookings (user_id, event_id, hold_id, total_paise, idempotency_key)
                VALUES (?, ?, ?, 45000, ?) RETURNING id
                """, Long.class, userId, eventId, holdId, idempotencyKey);
    }
}
