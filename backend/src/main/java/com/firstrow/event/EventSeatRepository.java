package com.firstrow.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Seat queries in plain SQL (JdbcClient) instead of JPA, on purpose:
 * this table is where correctness under concurrency matters, so we want to see and control
 * the exact SQL that runs (lazy expiry here; the atomic hold UPDATE in the holds feature).
 *
 * "Lazy expiry": a seat that is HELD but whose held_until is in the past counts as AVAILABLE.
 * We always compare against Postgres's now(), so the database clock is the single source of time.
 */
@Repository
public class EventSeatRepository {

    /** Effective status of a seat, with lazy expiry applied. Reused by every read query. */
    static final String EFFECTIVE_STATUS_SQL =
            "CASE WHEN status = 'HELD' AND held_until < now() THEN 'AVAILABLE' ELSE status END";

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public EventSeatRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SeatView> findSeatMap(long eventId) {
        return jdbc.sql("""
                        SELECT id, section, row_label, seat_number, price_tier, price_paise,
                               %s AS effective_status
                        FROM event_seats
                        WHERE event_id = :eventId
                        ORDER BY section, row_label, seat_number
                        """.formatted(EFFECTIVE_STATUS_SQL))
                .param("eventId", eventId)
                .query((rs, rowNum) -> new SeatView(
                        rs.getLong("id"),
                        rs.getString("section"),
                        rs.getString("row_label"),
                        rs.getInt("seat_number"),
                        rs.getString("price_tier"),
                        rs.getLong("price_paise"),
                        rs.getString("effective_status")))
                .list();
    }

    public SeatCounts countSeats(long eventId) {
        return jdbc.sql("""
                        SELECT count(*) AS total,
                               count(*) FILTER (WHERE %s = 'AVAILABLE') AS available
                        FROM event_seats
                        WHERE event_id = :eventId
                        """.formatted(EFFECTIVE_STATUS_SQL))
                .param("eventId", eventId)
                .query((rs, rowNum) -> new SeatCounts(rs.getInt("total"), rs.getInt("available")))
                .single();
    }

    /**
     * Inserts all seats of a new event as one JDBC batch (a few round trips instead of one per seat).
     * JPA saveAll() can't batch here: with IDENTITY ids Hibernate must insert rows one by one to read each id.
     */
    public void insertAll(long eventId, List<SeatSpec> seats) {
        jdbcTemplate.batchUpdate("""
                        INSERT INTO event_seats (event_id, section, row_label, seat_number, price_tier, price_paise)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                seats,
                500,
                (ps, seat) -> {
                    ps.setLong(1, eventId);
                    ps.setString(2, seat.section());
                    ps.setString(3, seat.row());
                    ps.setInt(4, seat.number());
                    ps.setString(5, seat.priceTier());
                    ps.setLong(6, seat.pricePaise());
                });
    }

    public record SeatCounts(int totalSeats, int availableSeats) {
    }
}
