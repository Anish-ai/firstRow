# Concurrency: how FirstRow never sells a seat twice

The core problem: when many users click the same seat at the same moment, **exactly one** must get it.
This document explains the race, the fix, the alternatives, and how it's tested.

Code: [`HoldRepository.holdSeats`](../backend/src/main/java/com/firstrow/hold/HoldRepository.java),
[`HoldService`](../backend/src/main/java/com/firstrow/hold/HoldService.java),
[`BookingService`](../backend/src/main/java/com/firstrow/booking/BookingService.java).
Tests: [`HoldConcurrencyIntegrationTest`](../backend/src/test/java/com/firstrow/hold/HoldConcurrencyIntegrationTest.java),
[`BookingIntegrationTest`](../backend/src/test/java/com/firstrow/booking/BookingIntegrationTest.java).

---

## 1. Why "check, then update" double-books

The obvious code reads the seat, checks it, then writes:

```java
Seat seat = repo.findById(id);              // SELECT
if (seat.status == AVAILABLE) {             // check
    seat.status = HELD; repo.save(seat);    // UPDATE
}
```

Two requests interleave like this:

```
time   Alice's request                      Bob's request
 t1    SELECT seat 7  -> AVAILABLE
 t2                                         SELECT seat 7  -> AVAILABLE
 t3    "it's free!"  UPDATE -> HELD (Alice)
 t4                                         "it's free!"  UPDATE -> HELD (Bob)   <-- overwrites Alice
 t5    200 OK "seat 7 is yours"             200 OK "seat 7 is yours"
```

Both checks happened before either write, so both saw a free seat. Nothing in the database stopped Bob's
UPDATE, because the UPDATE didn't repeat the check. This is a **race condition**: the result depends on timing.

It isn't theoretical. As an experiment I swapped the real code for this naive version and ran the
50-thread test: **10 users "won" the same seat**. Ten is the database connection pool size, so every request
that had a connection at that moment passed the check before anyone wrote.

## 2. The fix: one atomic conditional UPDATE

The check moves *into* the UPDATE:

```sql
UPDATE event_seats
SET status = 'HELD', hold_id = :holdId, held_until = :expiresAt, version = version + 1
WHERE event_id = :eventId
  AND id IN (:seatIds)
  AND (status = 'AVAILABLE' OR (status = 'HELD' AND held_until < now()))
RETURNING id, ...
```

If the number of rows returned ≠ the number of seats requested, the service throws, the transaction rolls
back (including any seats it did claim, and the hold row), and the client gets **409 Conflict** with
`unavailableSeatIds`. All or nothing.

**Why it's atomic.** Postgres takes a row lock on every row an UPDATE changes, held until commit. When a
second transaction wants to update a row that's locked:

```
time   Alice's transaction                         Bob's transaction
 t1    UPDATE seat 7 WHERE status='AVAILABLE'
       -> row matches, locked, set HELD
 t2                                                UPDATE seat 7 WHERE status='AVAILABLE'
                                                   -> row is locked: WAIT
 t3    COMMIT  (lock released)
 t4                                                re-reads the NEW row version, re-checks WHERE:
                                                   status is HELD, not expired -> 0 rows
 t5    201 Created                                 0 != 1 -> ROLLBACK -> 409 Conflict
```

The key is step t4. Under READ COMMITTED, Postgres doesn't apply Bob's UPDATE to the old row it first saw;
after waiting, it **re-evaluates the WHERE clause against the committed row**. There is no gap between check
and write for anyone to slip into. If Alice had rolled back instead, Bob would see `AVAILABLE` and win.

**Lazy expiry** is built into the same statement: a seat that is `HELD` with `held_until < now()` counts as
available. So correctness never depends on the cleanup job having run. That matters because on Render's free
tier the server sleeps and no job runs. The seat map query uses the same rule:
`CASE WHEN status='HELD' AND held_until < now() THEN 'AVAILABLE' ELSE status END`.
Every time comparison uses Postgres's `now()`, so the database clock is the only clock.

**Multi-seat deadlocks.** Two overlapping multi-seat holds can lock rows in opposite orders (Alice locks 1
then waits for 2; Bob locks 2 then waits for 1). Postgres detects the cycle within about a second and aborts
one transaction. Nothing was saved, so we return a retryable 409 instead of a 500
(`GlobalExceptionHandler.handleLockFailure`). Sorting seat ids and locking in that order would avoid most of
these; at our scale, detection is enough.

### The second safety net: a partial unique index

Even if a future bug bypassed the UPDATE, the database would refuse to sell a seat twice:

```sql
CREATE UNIQUE INDEX uq_booking_seats_active_seat ON booking_seats (event_seat_id) WHERE active;
```

A normal unique index on `event_seat_id` would be wrong: after a cancellation the seat must be bookable
again, so the same seat legitimately appears in several bookings over time. A **partial** index only
indexes rows matching its `WHERE`, so the rule becomes "at most one *active* booking per seat".

A partial index can only look at columns of its own table, not at `bookings.status`. So `booking_seats` has
its own `active` flag, set to `false` when the booking is cancelled (in the same transaction). Tested in
`SchemaIntegrationTest.seatCannotBeInTwoActiveBookings` and `seatCanBeRebookedAfterTheFirstBookingIsCancelled`.

## 3. Alternatives and when each is better

| Approach | How | Good | Bad | Better when |
|---|---|---|---|---|
| **Conditional UPDATE** (used) | Check inside the UPDATE's `WHERE` | One round trip, no extra locks, no retries; losers fail fast | The "check" must be expressible in SQL `WHERE` | State change is a simple condition on the row itself, like here |
| **Pessimistic lock** `SELECT … FOR UPDATE` | Lock rows first, check in Java, then UPDATE | Arbitrary Java logic between read and write | Holds locks longer; extra round trip; deadlock-prone without ordering | Complex rules that can't be one `WHERE` (e.g. "no single empty seat left between bookings") |
| **Optimistic lock** `@Version` | Read with version n; `UPDATE … WHERE version = n`; 0 rows → retry | No locks while the user thinks; great for edit forms | Under heavy contention most attempts fail and retry, so you waste work | Low contention, long "think time" between read and write (an admin editing an event) |
| **Distributed lock** (Redis `SET NX PX`, Redlock) | Lock a key like `seat:7` outside the DB | Works across several databases/services | New infrastructure; lock expiry vs GC pauses can let two holders in, so you still need DB-level checks | Resource isn't in one database (e.g. a seat reserved across two partner systems), or very hot keys you want to keep off the DB |

The `event_seats.version` column exists and is incremented on every change, so optimistic locking could be
used for future features without a migration.

## 4. Isolation level: READ COMMITTED (Postgres default)

Each **statement** sees data committed before it started; the next statement in the same transaction may
see newer commits.

- **Why it's enough:** the guarantee comes from row locks plus the WHERE re-check after waiting (section 2),
  which READ COMMITTED already provides. A stricter level adds no safety for a single conditional UPDATE.
- **Why not REPEATABLE READ / SERIALIZABLE:** in Postgres, a transaction at those levels that hits a
  concurrently updated row fails with a *serialization error* and must be retried by the application. In a
  rush that's lots of retries, plus retry code, for no extra correctness here.
- **Where READ COMMITTED actively helps:** idempotent booking confirmation (next section). The second of two
  duplicate requests waits on a lock, then its *next statement* sees the first request's committed booking.

## 5. Idempotency keys: a retried "confirm" must not book twice

The network can fail *after* the server booked but *before* the client got the answer. The client can't know
which happened, so it retries. Without protection a retry could book (and charge) twice.

The client generates a UUID once per checkout attempt and sends it as `Idempotency-Key` on every retry
(the frontend keeps it in `sessionStorage`, so even a page reload reuses it). The server:

1. **Seen this key?** `SELECT … FROM bookings WHERE user_id = ? AND idempotency_key = ?`. If found with the
   same hold, return the original booking with **200** and `Idempotent-Replayed: true`. Same key with a
   *different* hold: **422**, so a key is never silently reused for another purchase.
2. **Claim the hold:** `UPDATE holds SET status='CONVERTED' WHERE id=? AND user_id=? AND status='ACTIVE' AND
   expires_at > now()`. This locks the hold row.
3. **Book every seat still held by this hold**, and check the count against `holds.seat_count`. If a seat was
   lost (the hold expired at the last instant and someone re-held it), roll everything back: never under-book
   someone who's paying.
4. Mock payment, then insert `bookings` and `booking_seats`.

**Two identical requests at the same instant:**

```
time   Request A (key K)                         Request B (key K, same hold)
 t1    lookup K -> none
 t2                                              lookup K -> none
 t3    UPDATE hold -> ACTIVE, ok (row locked)
 t4                                              UPDATE hold -> locked: WAIT
 t5    book seats, insert booking, COMMIT
 t6                                              re-check: hold is CONVERTED -> 0 rows
 t7                                              lookup K again (fresh snapshot) -> A's booking
 t8    201 Created                               200 OK, same booking, Idempotent-Replayed
```

The **database is the final guard**: `UNIQUE (user_id, idempotency_key)` and `UNIQUE (hold_id)` on `bookings`
make a second booking impossible even if the code above had a bug.

Tested by `tenConcurrentConfirmsWithTheSameKeyCreateExactlyOneBooking` (10 threads, 1 booking, 9 replays).
With the step-7 re-lookup removed, that test fails: the losers get "already booked" instead of their booking.

## How it's verified

| What | Where | Result |
|---|---|---|
| 50 threads, 1 seat | `HoldConcurrencyIntegrationTest` | exactly 1 winner, 49 × 409, 1 hold row in the DB |
| 40 overlapping multi-seat holds | same | no seat shared between winners; losers hold nothing |
| Naive check-then-update (experiment) | same tests, code swapped | **10 winners** for one seat: test fails, as it should |
| 10 concurrent confirms, same key | `BookingIntegrationTest` | 1 booking, 9 idempotent replays |
| 200-user load test × 3 | [`load-tests/RESULTS.md`](../load-tests/RESULTS.md) | 0 double-booked seats (SQL-verified) |
