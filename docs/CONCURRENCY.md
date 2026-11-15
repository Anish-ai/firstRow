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

## 6. One hold per user per event, and at most 6 seats per person

**The problem it fixes.** The 6-seat limit used to apply to each *request*. Every "Hold" click created a new
hold, so one account could press Back, hold 6 more, and repeat: with the rate limit (10 holds/minute) and a
5-minute hold time, that's about 50 holds × 6 = 300 seats blocked by one person without paying.

**The rule now:**
- A user has **at most one active hold per event**. To change seats they *edit* that hold (`PUT /holds/{id}/seats`).
- A user can have **at most 6 seats per event, counting seats already booked**.
- Editing never extends the hold's expiry. Otherwise seats could be kept forever by editing every 4 minutes.

**Why the database enforces "one hold", not Java.** "Does this user already have a hold? If not, create one" is
check-then-act again: two tabs could both see "no hold". Instead there is a partial unique index:

```sql
CREATE UNIQUE INDEX uq_holds_one_active_per_user_event ON holds (user_id, event_id) WHERE status = 'ACTIVE';
```

If two requests from the same user insert a hold for the same event at once, the second `INSERT` **waits** on
the first one's uncommitted index entry. If the first commits, the second fails with a unique violation (409);
if the first rolls back (for example its seats were taken), the second goes ahead. Tested with 10 simultaneous
requests from one user: exactly 1 hold.

Two details make this work:
- **Lazy expiry first.** A hold whose time is up can still be `ACTIVE` until the cleanup job runs, and it would
  block the index. So `createHold` first expires *this user's* timed-out hold for the event (and frees its seats).
- **The inserted row acts as a lock.** Once our `INSERT` succeeded, no other hold request of this user for this
  event can get past its own `INSERT` until we finish. So counting the user's booked seats right after it can't
  go stale, and "booked + requested ≤ 6" is safe without any extra locking.

**Editing a hold** locks the hold row with `SELECT … FOR UPDATE`, then:
1. frees the seats no longer wanted,
2. claims the new seats with the **same atomic conditional `UPDATE`** as a new hold,
3. updates `holds.seat_count` (confirm compares against it, see V3).

If any new seat was taken, the method throws and the whole transaction rolls back, so the freed seats come
back too and the hold is exactly as it was. A confirm of the same hold also locks that row
(`UPDATE holds … SET status = 'CONVERTED'`), so an edit and a payment of one hold can't interleave: whichever
comes second sees the other's result.

## 7. Admin edits: optimistic locking with `@Version`

Admins edit rarely and think for a while between loading and saving, which is the case optimistic locking is
for (see section 3). `events.version` is a JPA `@Version` column. The edit request carries the version the form
was loaded with:
- If it doesn't match the current version, the API returns 409 ("changed by someone else, reload").
- If another edit commits between our read and our write, Hibernate's `UPDATE … WHERE id = ? AND version = ?`
  matches no row and throws, which is also turned into a 409.

Nobody's change is silently overwritten, and no locks are held while an admin is typing.

**Cancelling an event** releases its active holds *before* cancelling its bookings, in one transaction. A
confirm running at that moment holds its hold row's lock, so the release waits for it. When it commits, its new
booking is visible to the next statement (READ COMMITTED) and is cancelled too. Confirm also refuses holds of
events that are no longer `SCHEDULED`.

## How it's verified

| What | Where | Result |
|---|---|---|
| 50 threads, 1 seat | `HoldConcurrencyIntegrationTest` | exactly 1 winner, 49 × 409, 1 hold row in the DB |
| 40 overlapping multi-seat holds | same | no seat shared between winners; losers hold nothing |
| Naive check-then-update (experiment) | same tests, code swapped | **10 winners** for one seat: test fails, as it should |
| 10 concurrent confirms, same key | `BookingIntegrationTest` | 1 booking, 9 idempotent replays |
| 10 simultaneous holds, same user and event | `HoldEditIntegrationTest` | exactly 1 hold |
| Edit that hits a taken seat | same | 409, hold unchanged (freed seats restored) |
| Booked seats count towards 6 | same | 4 booked + 3 more → 409; + 2 more → OK |
| Admin edit with a stale version | `AdminEventEditIntegrationTest` | 409, first edit kept |
| 200-user load test × 3 | [`load-tests/RESULTS.md`](../load-tests/RESULTS.md) | 0 double-booked seats (SQL-verified) |
