-- V2 schedules the demo events relative to the moment the database was created, so their clock times depend
-- on when that happened (a database created at 03:00 UTC got shows at 3:30 am in India).
-- Keep each demo event's date (in India time) but move it to a realistic evening start time.
-- Only the four seeded demo events are touched; admin-created events keep whatever time they were given.
UPDATE events e
SET starts_at = (date_trunc('day', e.starts_at AT TIME ZONE 'Asia/Kolkata') + t.local_time) AT TIME ZONE 'Asia/Kolkata'
FROM (VALUES
        ('Starfall — Premiere Night',                    interval '21 hours'),
        ('The Quiet Orbit — Premiere Night',             interval '18 hours 30 minutes'),
        ('City Lions vs Harbor Hawks — Final',           interval '19 hours 30 minutes'),
        ('Desert Falcons vs Coastal Kings — Semi-final', interval '19 hours 30 minutes')
     ) AS t (title, local_time)
WHERE e.title = t.title
  AND e.status = 'SCHEDULED';
