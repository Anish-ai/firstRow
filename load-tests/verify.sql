-- Correctness check after a load test: run against the database the backend used.
--   docker compose -f infra/docker-compose.yml exec -T postgres psql -U firstrow -d firstrow < load-tests/verify.sql
-- Looks at the most recent "Load Test Rush" event.

\echo '--- Seats booked in more than one active booking, across ALL events (must be 0 rows) ---'
SELECT bs.event_seat_id, count(*) AS active_bookings
FROM booking_seats bs
WHERE bs.active
GROUP BY bs.event_seat_id
HAVING count(*) > 1;

\echo '--- Latest load-test event: every number on a line must agree ---'
WITH ev AS (
    SELECT id, title FROM events WHERE title LIKE 'Load Test Rush%' ORDER BY id DESC LIMIT 1
)
SELECT
    (SELECT title FROM ev) AS event,
    (SELECT count(*) FROM event_seats WHERE event_id = (SELECT id FROM ev)) AS total_seats,
    (SELECT count(*) FROM event_seats WHERE event_id = (SELECT id FROM ev) AND status = 'BOOKED') AS seats_marked_booked,
    (SELECT count(*) FROM booking_seats bs JOIN bookings b ON b.id = bs.booking_id
       WHERE b.event_id = (SELECT id FROM ev) AND b.status = 'CONFIRMED' AND bs.active) AS seats_in_confirmed_bookings,
    (SELECT count(DISTINCT bs.event_seat_id) FROM booking_seats bs JOIN bookings b ON b.id = bs.booking_id
       WHERE b.event_id = (SELECT id FROM ev) AND bs.active) AS distinct_seats_in_bookings,
    (SELECT count(*) FROM bookings WHERE event_id = (SELECT id FROM ev) AND status = 'CONFIRMED') AS confirmed_bookings;

\echo '--- Bookings whose total does not equal the sum of their seat prices (must be 0 rows) ---'
SELECT b.id, b.total_paise, sum(s.price_paise) AS seats_sum
FROM bookings b
JOIN booking_seats bs ON bs.booking_id = b.id
JOIN event_seats s ON s.id = bs.event_seat_id
WHERE b.event_id = (SELECT id FROM events WHERE title LIKE 'Load Test Rush%' ORDER BY id DESC LIMIT 1)
GROUP BY b.id, b.total_paise
HAVING b.total_paise <> sum(s.price_paise);

\echo '--- BOOKED seats whose hold_id is not the hold of a confirmed booking (must be 0 rows) ---'
SELECT s.id
FROM event_seats s
LEFT JOIN bookings b ON b.hold_id = s.hold_id AND b.status = 'CONFIRMED'
WHERE s.event_id = (SELECT id FROM events WHERE title LIKE 'Load Test Rush%' ORDER BY id DESC LIMIT 1)
  AND s.status = 'BOOKED'
  AND b.id IS NULL;
