package com.firstrow.admin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL run when an admin cancels an event, inside the same transaction that marks the event CANCELLED.
 * Order matters (see AdminService#cancelEvent): holds first, then bookings.
 */
@Repository
public class EventCancellationRepository {

    private final JdbcClient jdbc;

    public EventCancellationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * ACTIVE holds -> RELEASED. If a confirm of one of these holds is running right now, this UPDATE waits
     * for its row lock; once that confirm commits, the hold is CONVERTED and skipped here, and its new
     * booking is visible to the next statement (READ COMMITTED), so it gets cancelled too.
     */
    public int releaseActiveHolds(long eventId) {
        return jdbc.sql("UPDATE holds SET status = 'RELEASED' WHERE event_id = :eventId AND status = 'ACTIVE'")
                .param("eventId", eventId)
                .update();
    }

    /** CONFIRMED bookings -> CANCELLED, and their seats leave the "one active booking per seat" index. */
    public int cancelBookings(long eventId) {
        return jdbc.sql("""
                        WITH cancelled AS (
                            UPDATE bookings SET status = 'CANCELLED'
                            WHERE event_id = :eventId AND status = 'CONFIRMED'
                            RETURNING id)
                        UPDATE booking_seats SET active = FALSE
                        WHERE booking_id IN (SELECT id FROM cancelled)
                        """)
                .param("eventId", eventId)
                .update();
    }

    /** Every seat back to AVAILABLE, so the data stays consistent with "no holds, no bookings". */
    public int freeAllSeats(long eventId) {
        return jdbc.sql("""
                        UPDATE event_seats SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL,
                                               version = version + 1
                        WHERE event_id = :eventId AND status <> 'AVAILABLE'
                        """)
                .param("eventId", eventId)
                .update();
    }
}
