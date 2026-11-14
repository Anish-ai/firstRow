# Interview notes

Likely questions about FirstRow, with short answers in my own words. Every number here was measured;
where it came from is noted.

---

### 1. What is FirstRow, in 30 seconds?

A seat-booking system for film premieres and live matches. You browse events, hold up to 6 seats for 5
minutes, and confirm with a mock payment. The interesting part is concurrency: when hundreds of people click
the same seat at once, exactly one gets it. It's Spring Boot 3 on Java 21 with Postgres, a React/TypeScript
frontend, tests with Testcontainers, CI on GitHub Actions, Prometheus/Grafana monitoring, and a k6 load test.

### 2. How do you stop two people booking the same seat?

One atomic conditional UPDATE: `UPDATE event_seats SET status='HELD' … WHERE id IN (…) AND (status='AVAILABLE'
OR (status='HELD' AND held_until < now()))`. If fewer rows change than I asked for, I roll back and return 409
with the taken seat ids. Postgres locks each row it updates; a second transaction waits on that lock and then
re-checks the WHERE against the committed row, sees HELD, and matches nothing. There's no gap between check
and write. A partial unique index on active `booking_seats` is a second safety net at the database level.

### 3. Why not just SELECT, check in Java, then UPDATE?

Because two requests can both SELECT before either UPDATEs, and both see "available". I proved it: I swapped
in that naive version and my 50-thread test gave **10 winners for one seat**. Ten was the connection pool
size, so every request holding a connection at that instant passed the check. With the real code it's always
exactly 1 winner and 49 conflicts.

### 4. Which isolation level do you use, and why not SERIALIZABLE?

Postgres's default, READ COMMITTED. The conditional UPDATE is already safe there because of row locks plus the
re-check after waiting. SERIALIZABLE wouldn't add safety for this; in a rush it would just turn conflicts into
serialization errors I'd have to catch and retry. READ COMMITTED also helps my idempotency logic: after waiting
on a lock, the next statement sees what the other transaction committed.

### 5. Pessimistic locking, optimistic locking, Redis locks: when would you use each?

- `SELECT … FOR UPDATE` when the rule can't be written as one WHERE clause, e.g. "don't leave a single empty
  seat between two bookings". It holds locks longer and needs a consistent lock order.
- Optimistic `@Version` when contention is low and there's think time, like an admin editing an event. Under a
  rush, most attempts would fail and retry.
- A Redis lock when the resource isn't in one database. It's extra infrastructure, and lock expiry during a GC
  pause can let two holders in, so you'd still want a database check underneath.

For seats in one Postgres, the conditional UPDATE is the simplest correct option.

### 6. What's an idempotency key and why do you need one?

If the network drops after I've booked but before the client hears back, the client retries. The client sends
the same UUID (`Idempotency-Key`) on every retry of one checkout; I store it with the booking under
`UNIQUE (user_id, idempotency_key)`. A repeat returns the original booking with 200. If two identical requests
arrive at once, the second waits on the hold row's lock, then finds the first one's booking and returns it.
Tested with 10 simultaneous confirms: exactly 1 booking and 9 replays. The frontend keeps the key in
`sessionStorage`, so even a page reload reuses it.

### 7. Holds expire after 5 minutes. Why "lazy expiry" instead of just a scheduled job?

On Render's free tier the server sleeps, so a job might not run for an hour. Lazy expiry treats
`HELD AND held_until < now()` as available in every query, including the hold UPDATE itself, so correctness
never depends on the job. The job runs every 30 seconds only as housekeeping. I use Postgres's `now()`
everywhere, so there's one clock and no app-server clock skew.

### 8. Which indexes did you add, and why?

Each one serves a specific query: `(event_id, status)` on seats for the seat map and the hold UPDATE;
`(hold_id)` for confirming or releasing a hold; `(status, expires_at)` on holds for the cleanup job;
`(user_id, created_at DESC)` on bookings for "my bookings"; `(type, starts_at)` on events for the filtered,
sorted list. The unique ones encode rules: one seat per position per event, one booking per hold, one key per
user, and the partial `UNIQUE (event_seat_id) WHERE active`.

### 9. How does authentication work?

Signup and login return an HS256 JWT signed with a server secret, valid for 1 hour, carrying the user id and
role. On each request, Spring Security's `BearerTokenAuthenticationFilter` verifies the signature, issuer and
expiry, then maps the role claim to `ROLE_ADMIN` or `ROLE_USER`. URL rules allow, return 401 or return 403;
everything not listed requires a login. I used Spring's built-in resource-server support rather than writing
my own filter, so the security-critical code isn't mine. Passwords are BCrypt; unknown email and wrong
password give the same error and take about the same time.

### 10. Your JWT is in localStorage. Isn't that insecure?

It's a trade-off. Any script on the page can read localStorage, so an XSS bug could steal the token. An
httpOnly cookie can't be read by scripts, but with the frontend and API on different domains I'd need
`SameSite=None` cookies and CSRF protection. I chose the bearer token, rely on React escaping output, and keep
tokens short-lived. With one domain I'd switch to an httpOnly cookie.

### 11. How did you test this?

- **Unit tests** with Mockito for the business rules: max 6 seats, totals, idempotency branches, layout validation, rate limiter.
- **Integration tests** with **Testcontainers** running real Postgres 16. H2 behaves differently for partial indexes and locking, which are exactly what I'm testing.
- **Concurrency tests:** 50 threads on 1 seat, and 40 overlapping multi-seat holds.
- **Frontend:** 17 Vitest + Testing Library tests, including keyboard use and 409 handling.
- **Alert rules:** unit-tested with promtool.
- **Load test:** k6 with SQL checks afterwards.

That's 112 backend tests, with 99.6% line coverage on the service layer (JaCoCo). I also checked that the key
tests really can fail: I broke the code on purpose and made sure each test went red.

### 12. Tell me about a bug your tests caught.

Two good ones:
- A `@Transactional` test claimed a rolled-back hold had left a seat HELD. That wasn't an app bug: in a
  transactional test the service joins the test's transaction, so its rollback doesn't happen until the test
  ends. I moved that check to a non-transactional test.
- My `holds.created` metric was exported to Prometheus as `holds_total`. `_created` is a reserved suffix in
  OpenMetrics and the client silently stripped it. The dashboard would have shown an empty panel. A test that
  reads the exact exported names caught it, and I renamed the counter to `holds.granted`.

### 13. What did the load test show?

200 virtual users rushed a fresh 200-seat event, 3 runs on my laptop in Docker. Every run sold out all 200
seats in about a second, with **0 double-booked seats** verified in SQL. About 56% of hold attempts got a 409,
which is expected when demand is about 2.5× supply. Hold p95 was 88–216 ms (the higher figure on a freshly
started JVM), with 0 failed requests, and Prometheus's counters matched k6's exactly. It's laptop data, so I
present it as correctness under contention, not production capacity.

### 14. What breaks first if this gets popular?

On the free tier: CPU. With Render's 0.1 CPU, a cold start took 329 s in my simulation; I got it to 114 s with
C1-only JIT and a CDS archive. Beyond that:
1. **Seat-map polling.** Every viewer fetches the seat map every 3 s. A 400-seat map is 44.7 KB, which I got
   down to 1.9 KB by enabling gzip, but 10,000 viewers is still about 3,300 requests per second, almost all
   returning unchanged data.
2. **Database connections.** A pool of 10 per instance, and small managed databases cap total connections.
3. **The in-memory rate limiter.** It stops being correct with more than one instance.

### 15. How would you scale to a million users?

The API is stateless, so I'd run several instances behind a load balancer.
- **Seat maps:** cache for a second or two, add ETags so unchanged maps return 304, then push changes over
  SSE from a pub/sub channel instead of polling.
- **Database connections:** put PgBouncer in front of Postgres, and use read replicas for event browsing.
- **Rate limiting:** move the token buckets to Redis.
- **Mega on-sales:** a virtual waiting room that admits users in batches.

The write path doesn't need to change: contention is per seat row, so different events and different seats
don't block each other. Later I'd partition `event_seats` by event and archive past events.

### 16. Polling vs Server-Sent Events vs WebSockets?

Polling every 3 s is simple, works through every proxy, and makes the server stateless. It costs a request
per viewer every 3 s even when nothing changed, and updates arrive up to 3 s late. SSE keeps one HTTP
connection open and the server pushes only changes; it's one-way, which is all a seat map needs. WebSockets
are two-way, which I don't need. For SSE across several instances you need a pub/sub such as Postgres
LISTEN/NOTIFY or Redis to tell every instance about a change.

### 17. Why plain SQL for seats but JPA elsewhere?

For seats, holds and bookings, the exact SQL statement *is* the correctness guarantee, so I want it visible in
the code, not generated. JPA also can't batch inserts with IDENTITY ids, and an event can have hundreds of
seats; I insert them with one JDBC batch. For simple entities such as users, venues and events, JPA saves
boilerplate, and `@EntityGraph` avoids N+1 queries on the event list.

### 18. How did you make the seat map accessible?

- Every seat is a real `<button>` with a label like "Section A, Row C, Seat 7, ₹450, available", and `aria-pressed` for selection.
- Unavailable seats use `aria-disabled` rather than `disabled`, so keyboard and screen-reader users can still reach them and hear why they can't pick them.
- A roving tabindex makes each section one Tab stop, with arrow keys inside. Otherwise a 400-seat section would need 400 Tab presses.
- Held and booked seats have their own icons and stripes, so colour is never the only signal. Focus is always visible.
- Screen readers announce through ARIA live regions when a hold succeeds, fails or expires.
- The `jsx-a11y` linter runs in CI.

### 19. How do you debug a production issue here?

Every response and every error body carries a request ID, and every log line is prefixed with it. A user
reports "booking failed, request id abc…"; I search the logs for that ID and see the whole story. Grafana shows
whether it's widespread: error rate, p95 latency, the conflict ratio. Two alerts are defined: more than 5% 5xx
for 2 minutes, and more than 80% hold conflicts for 1 minute.

### 20. What would you do next?

Server-Sent Events instead of polling. Sorting seat ids before the hold UPDATE to avoid most deadlocks. A
transactional outbox so a real payment provider and the database can't disagree. Moving the rate limiter to
Redis. A `jlink`-trimmed JRE for a smaller image, tested carefully, because a missing module (like the TLS
crypto module Neon needs) only shows up at runtime. And Playwright end-to-end tests in CI.
