-- V2: demo data so `docker compose up` shows something on a fresh machine.
-- All names are fictional. Event dates are relative to "now" so they are always upcoming.

INSERT INTO venues (name, city) VALUES
    ('Aurora Grand Cinema', 'Mumbai'),
    ('Riverside Arena', 'Bengaluru');

INSERT INTO events (venue_id, title, type, starts_at)
SELECT v.id, e.title, e.type, date_trunc('hour', now()) + e.starts_in
FROM (VALUES
        ('Aurora Grand Cinema', 'Starfall — Premiere Night',                  'PREMIERE', interval '10 days 19 hours'),
        ('Aurora Grand Cinema', 'The Quiet Orbit — Premiere Night',           'PREMIERE', interval '24 days 20 hours'),
        ('Riverside Arena',     'City Lions vs Harbor Hawks — Final',         'MATCH',    interval '14 days 18 hours'),
        ('Riverside Arena',     'Desert Falcons vs Coastal Kings — Semi-final', 'MATCH',  interval '7 days 15 hours')
     ) AS e(venue_name, title, type, starts_in)
JOIN venues v ON v.name = e.venue_name;

-- Premieres: one section "A", rows A..F, 14 seats per row.
-- Rows A-B are RECLINER (₹750), rows C-F are GOLD (₹450).
INSERT INTO event_seats (event_id, section, row_label, seat_number, price_tier, price_paise)
SELECT ev.id,
       'A',
       chr(64 + r),
       s,
       CASE WHEN r <= 2 THEN 'RECLINER' ELSE 'GOLD' END,
       CASE WHEN r <= 2 THEN 75000 ELSE 45000 END
FROM events ev
CROSS JOIN generate_series(1, 6)  AS r
CROSS JOIN generate_series(1, 14) AS s
WHERE ev.type = 'PREMIERE';

-- Matches: sections NORTH and SOUTH, rows A..J, 20 seats per row.
-- Rows A-C are PAVILION (₹1,500), rows D-J are STAND (₹600).
INSERT INTO event_seats (event_id, section, row_label, seat_number, price_tier, price_paise)
SELECT ev.id,
       sec,
       chr(64 + r),
       s,
       CASE WHEN r <= 3 THEN 'PAVILION' ELSE 'STAND' END,
       CASE WHEN r <= 3 THEN 150000 ELSE 60000 END
FROM events ev
CROSS JOIN unnest(ARRAY['NORTH', 'SOUTH']) AS sec
CROSS JOIN generate_series(1, 10) AS r
CROSS JOIN generate_series(1, 20) AS s
WHERE ev.type = 'MATCH';
