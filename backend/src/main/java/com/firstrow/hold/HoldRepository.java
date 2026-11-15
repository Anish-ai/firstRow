package com.firstrow.hold;

import com.firstrow.event.SeatView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
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

    /**
     * Expires this user's ACTIVE-but-timed-out hold for this event (lazy expiry) and frees its seats.
     * Runs before inserting a new hold, so an old hold nobody cleaned up yet can't trip the
     * "one active hold per user per event" unique index.
     */
    public int expireStaleHold(long userId, long eventId) {
        return jdbc.sql("""
                        WITH expired AS (
                            UPDATE holds SET status = 'EXPIRED'
                            WHERE user_id = :userId AND event_id = :eventId AND status = 'ACTIVE'
                              AND expires_at <= now()
                            RETURNING id)
                        UPDATE event_seats SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL
                        WHERE hold_id IN (SELECT id FROM expired) AND status = 'HELD'
                        """)
                .param("userId", userId)
                .param("eventId", eventId)
                .update();
    }

    /** Seats this user has in CONFIRMED bookings for this event (they count towards the per-event limit). */
    public int countBookedSeats(long userId, long eventId) {
        return jdbc.sql("""
                        SELECT count(*) FROM booking_seats bs
                        JOIN bookings b ON b.id = bs.booking_id
                        WHERE b.user_id = :userId AND b.event_id = :eventId AND bs.active
                        """)
                .param("userId", userId)
                .param("eventId", eventId)
                .query(Integer.class)
                .single();
    }

    /**
     * The user's hold if it is ACTIVE and not expired, with its row LOCKED until the transaction ends.
     * A concurrent confirm or edit of the same hold waits here; under READ COMMITTED, once it gets the
     * lock Postgres re-checks the WHERE clause, so a hold that was just booked or released is not returned.
     */
    public Optional<ActiveHold> lockActiveHold(UUID holdId, long userId) {
        return jdbc.sql("""
                        SELECT id, event_id, expires_at FROM holds
                        WHERE id = :holdId AND user_id = :userId AND status = 'ACTIVE' AND expires_at > now()
                        FOR UPDATE
                        """)
                .param("holdId", holdId)
                .param("userId", userId)
                .query(HoldRepository::mapActiveHold)
                .optional();
    }

    /** The user's ACTIVE, unexpired hold with this id (read only). */
    public Optional<ActiveHold> findActiveHold(UUID holdId, long userId) {
        return jdbc.sql("""
                        SELECT id, event_id, expires_at FROM holds
                        WHERE id = :holdId AND user_id = :userId AND status = 'ACTIVE' AND expires_at > now()
                        """)
                .param("holdId", holdId)
                .param("userId", userId)
                .query(HoldRepository::mapActiveHold)
                .optional();
    }

    /** The user's ACTIVE, unexpired hold for this event, if any (there is at most one, see V5). */
    public Optional<ActiveHold> findActiveHoldForEvent(long userId, long eventId) {
        return jdbc.sql("""
                        SELECT id, event_id, expires_at FROM holds
                        WHERE user_id = :userId AND event_id = :eventId AND status = 'ACTIVE' AND expires_at > now()
                        """)
                .param("userId", userId)
                .param("eventId", eventId)
                .query(HoldRepository::mapActiveHold)
                .optional();
    }

    /** Seats currently held by this hold. */
    public List<SeatView> findHeldSeats(UUID holdId) {
        return jdbc.sql("""
                        SELECT id, section, row_label, seat_number, price_tier, price_paise FROM event_seats
                        WHERE hold_id = :holdId AND status = 'HELD'
                        ORDER BY section, row_label, seat_number
                        """)
                .param("holdId", holdId)
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

    /** Gives back some of a hold's seats (used when the user edits their hold). */
    public int freeSeats(UUID holdId, Collection<Long> seatIds) {
        return jdbc.sql("""
                        UPDATE event_seats SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL
                        WHERE hold_id = :holdId AND status = 'HELD' AND id IN (:seatIds)
                        """)
                .param("holdId", holdId)
                .param("seatIds", seatIds)
                .update();
    }

    /** Keeps holds.seat_count in step with the seats after an edit (confirm compares against it, see V3). */
    public void updateSeatCount(UUID holdId, int seatCount) {
        jdbc.sql("UPDATE holds SET seat_count = :seatCount WHERE id = :holdId")
                .param("seatCount", seatCount)
                .param("holdId", holdId)
                .update();
    }

    /** Status of the user's hold and whether its time is up. Empty if unknown or someone else's. */
    public Optional<HoldState> findState(UUID holdId, long userId) {
        return jdbc.sql("SELECT status, expires_at <= now() AS expired FROM holds WHERE id = :holdId AND user_id = :userId")
                .param("holdId", holdId)
                .param("userId", userId)
                .query((rs, rowNum) -> new HoldState(rs.getString("status"), rs.getBoolean("expired")))
                .optional();
    }

    private static ActiveHold mapActiveHold(ResultSet rs, int rowNum) throws SQLException {
        return new ActiveHold(rs.getObject("id", UUID.class), rs.getLong("event_id"),
                rs.getObject("expires_at", OffsetDateTime.class));
    }

    public record ActiveHold(UUID id, long eventId, OffsetDateTime expiresAt) {
    }

    public record HoldState(String status, boolean expired) {
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
