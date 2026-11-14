package com.firstrow;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * A throwaway event with its own seats, for tests that must COMMIT (concurrency, rollback checks)
 * and therefore can't use @Transactional rollback. Seed data stays untouched; call {@link #delete()} afterwards.
 * Seats are one row "A" of {@code seatCount} STAND seats at ₹600 each.
 */
public final class PrivateEvent {

    public static final long SEAT_PRICE_PAISE = 60_000;

    private final JdbcTemplate jdbc;
    private final long id;
    private final List<Long> seatIds;

    private PrivateEvent(JdbcTemplate jdbc, long id, List<Long> seatIds) {
        this.jdbc = jdbc;
        this.id = id;
        this.seatIds = seatIds;
    }

    public static PrivateEvent create(JdbcTemplate jdbc, int seatCount) {
        long id = jdbc.queryForObject("""
                INSERT INTO events (venue_id, title, type, starts_at)
                VALUES ((SELECT min(id) FROM venues), 'Private Test Match', 'MATCH', now() + interval '30 days')
                RETURNING id
                """, Long.class);
        jdbc.update("""
                INSERT INTO event_seats (event_id, section, row_label, seat_number, price_tier, price_paise)
                SELECT ?, 'A', 'A', s, 'STAND', ? FROM generate_series(1, ?) AS s
                """, id, SEAT_PRICE_PAISE, seatCount);
        List<Long> seatIds = jdbc.queryForList(
                "SELECT id FROM event_seats WHERE event_id = ? ORDER BY seat_number", Long.class, id);
        return new PrivateEvent(jdbc, id, seatIds);
    }

    public long id() {
        return id;
    }

    public List<Long> seatIds() {
        return seatIds;
    }

    /** Deletes everything that references this event, children first (foreign keys). */
    public void delete() {
        jdbc.update("DELETE FROM booking_seats WHERE booking_id IN (SELECT id FROM bookings WHERE event_id = ?)", id);
        jdbc.update("DELETE FROM bookings WHERE event_id = ?", id);
        jdbc.update("DELETE FROM event_seats WHERE event_id = ?", id);
        jdbc.update("DELETE FROM holds WHERE event_id = ?", id);
        jdbc.update("DELETE FROM events WHERE id = ?", id);
    }
}
