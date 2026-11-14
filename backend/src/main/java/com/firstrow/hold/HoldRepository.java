package com.firstrow.hold;

import com.firstrow.event.SeatView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * All hold-related SQL. Plain SQL on purpose: the correctness of seat holding depends on
 * the exact statements below, so they should be visible, not generated.
 * All times use Postgres's now(), so the database clock is the only clock that matters.
 */
@Repository
public class HoldRepository {

    private final JdbcClient jdbc;

    public HoldRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts an ACTIVE hold expiring {@code ttl} from now (DB time). Returns its expiry. */
    public OffsetDateTime insertHold(UUID holdId, long userId, long eventId, int seatCount, Duration ttl) {
        return jdbc.sql("""
                        INSERT INTO holds (id, user_id, event_id, seat_count, expires_at, status)
                        VALUES (:holdId, :userId, :eventId, :seatCount,
                                now() + :ttlSeconds * interval '1 second', 'ACTIVE')
                        RETURNING expires_at
                        """)
                .param("holdId", holdId)
                .param("userId", userId)
                .param("eventId", eventId)
                .param("seatCount", seatCount)
                .param("ttlSeconds", ttl.toSeconds())
                .query((rs, rowNum) -> rs.getObject("expires_at", OffsetDateTime.class))
                .single();
    }

    /**
     * THE concurrency-critical statement. One UPDATE both checks and claims the seats:
     * a seat is claimed only if it is AVAILABLE, or HELD by a hold that has already expired (lazy expiry).
     *
     * Why this is safe: Postgres locks each row it updates. If two transactions race for the same seat,
     * the second one waits for the first to commit, then RE-CHECKS the WHERE clause against the new
     * row version. The seat is now HELD (not expired), so the second UPDATE skips it.
     * There is no gap between "check" and "update" for another request to slip into.
     *
     * Returns the seats actually claimed; the caller compares that with what was requested.
     */
    public List<SeatView> holdSeats(UUID holdId, long eventId, Collection<Long> seatIds, OffsetDateTime expiresAt) {
        return jdbc.sql("""
                        UPDATE event_seats
                        SET status = 'HELD', hold_id = :holdId, held_until = :expiresAt, version = version + 1
                        WHERE event_id = :eventId
                          AND id IN (:seatIds)
                          AND (status = 'AVAILABLE' OR (status = 'HELD' AND held_until < now()))
                        RETURNING id, section, row_label, seat_number, price_tier, price_paise
                        """)
                .param("holdId", holdId)
                .param("expiresAt", expiresAt)
                .param("eventId", eventId)
                .param("seatIds", seatIds)
                .query((rs, rowNum) -> new SeatView(
                        rs.getLong("id"),
                        rs.getString("section"),
                        rs.getString("row_label"),
                        rs.getInt("seat_number"),
                        rs.getString("price_tier"),
                        rs.getLong("price_paise"),
                        "HELD"))
                .list();
    }

    /** Which of these seat ids exist in this event (used to tell "taken" apart from "not a seat here"). */
    public Set<Long> findExistingSeatIds(long eventId, Collection<Long> seatIds) {
        return new HashSet<>(jdbc.sql("SELECT id FROM event_seats WHERE event_id = :eventId AND id IN (:seatIds)")
                .param("eventId", eventId)
                .param("seatIds", seatIds)
                .query(Long.class)
                .list());
    }

    /** ACTIVE -> RELEASED, only for the hold's owner. Returns rows changed (0 or 1). */
    public int markReleased(UUID holdId, long userId) {
        return jdbc.sql("""
                        UPDATE holds SET status = 'RELEASED'
                        WHERE id = :holdId AND user_id = :userId AND status = 'ACTIVE'
                        """)
                .param("holdId", holdId)
                .param("userId", userId)
                .update();
    }

    /** Status of a hold, only if it belongs to this user (other users' holds look "not found"). */
    public Optional<String> findStatus(UUID holdId, long userId) {
        return jdbc.sql("SELECT status FROM holds WHERE id = :holdId AND user_id = :userId")
                .param("holdId", holdId)
                .param("userId", userId)
                .query(String.class)
                .optional();
    }

    /** Frees the seats still held by this hold (seats re-held by someone else after expiry are untouched). */
    public int freeSeats(UUID holdId) {
        return jdbc.sql("""
                        UPDATE event_seats SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL
                        WHERE hold_id = :holdId AND status = 'HELD'
                        """)
                .param("holdId", holdId)
                .update();
    }

    /** Cleanup job, step 1: free every seat whose hold time has passed. */
    public int freeExpiredSeats() {
        return jdbc.sql("""
                        UPDATE event_seats SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL
                        WHERE status = 'HELD' AND held_until < now()
                        """)
                .update();
    }

    /** Cleanup job, step 2: mark ACTIVE holds past their expiry as EXPIRED. */
    public int markExpiredHolds() {
        return jdbc.sql("UPDATE holds SET status = 'EXPIRED' WHERE status = 'ACTIVE' AND expires_at < now()")
                .update();
    }
}
