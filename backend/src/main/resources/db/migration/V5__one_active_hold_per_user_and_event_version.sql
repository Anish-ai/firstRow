-- V5: (1) a user can have at most ONE active hold per event; (2) optimistic locking for admin event edits.

-- (1) Before the unique index can be created, tidy up existing data:
--     expire ACTIVE holds whose time is up, and (should any exist) every active hold of a user for an
--     event except the newest one...
UPDATE holds h
SET status = 'EXPIRED'
WHERE h.status = 'ACTIVE'
  AND (h.expires_at <= now()
       OR EXISTS (SELECT 1
                  FROM holds newer
                  WHERE newer.user_id = h.user_id
                    AND newer.event_id = h.event_id
                    AND newer.status = 'ACTIVE'
                    AND newer.expires_at > now()
                    AND (newer.expires_at, newer.id) > (h.expires_at, h.id)));

-- ...and free the seats those holds still had.
UPDATE event_seats s
SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL
FROM holds h
WHERE s.hold_id = h.id
  AND s.status = 'HELD'
  AND h.status IN ('EXPIRED', 'RELEASED');

-- The rule itself, enforced by the database: two concurrent "hold" requests from the same user for the
-- same event can't both succeed; the second INSERT waits for the first and then fails with a unique
-- violation (which the API turns into a 409). Expired-but-still-ACTIVE rows are expired by the
-- application just before it inserts, so they never block a new hold.
CREATE UNIQUE INDEX uq_holds_one_active_per_user_event ON holds (user_id, event_id) WHERE status = 'ACTIVE';

-- (2) JPA @Version column: an admin saving an edit based on an out-of-date copy of the event gets a 409
--     instead of silently overwriting someone else's change.
ALTER TABLE events ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
