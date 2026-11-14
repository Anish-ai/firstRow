-- V3: remember how many seats each hold claimed.
-- When a hold is confirmed, every one of its seats must still be held by it. Comparing the number of
-- seats we turn into BOOKED against this count catches the (rare) case where the hold expired at the
-- last moment and another user re-held one of its seats.
-- Existing holds get 0; the application always sets the real value for new ones.

ALTER TABLE holds ADD COLUMN seat_count INT NOT NULL DEFAULT 0 CHECK (seat_count >= 0);
