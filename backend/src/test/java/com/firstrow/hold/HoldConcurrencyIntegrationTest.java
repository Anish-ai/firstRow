package com.firstrow.hold;

import com.firstrow.AbstractIntegrationTest;
import com.firstrow.PrivateEvent;
import com.firstrow.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The race-condition tests. NOT @Transactional on purpose: every thread must run and commit its own
 * transaction against the real Postgres, exactly like concurrent HTTP requests would.
 * Each test creates a private event (and deletes it afterwards) so seed data is never touched.
 */
class HoldConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private HoldService holdService;

    @Autowired
    private HoldExpiryJob expiryJob;

    private PrivateEvent event;
    private long eventId;
    private List<Long> seatIds;

    @BeforeEach
    void createPrivateEvent() {
        event = PrivateEvent.create(jdbc, 20);
        eventId = event.id();
        seatIds = event.seatIds();
    }

    @AfterEach
    void deletePrivateEvent() {
        event.delete();
    }

    @Test
    void fiftyUsersRaceForOneSeatAndExactlyOneWins() throws Exception {
        long seat = seatIds.getFirst();
        List<Long> users = createUsers(50);

        List<Outcome> outcomes = runAllAtOnce(users.stream()
                .map(userId -> (Callable<Outcome>) () -> attempt(userId, List.of(seat)))
                .toList());

        assertThat(outcomes).filteredOn(Outcome::won).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.won()).hasSize(49)
                .allMatch(o -> o.error() instanceof SeatsUnavailableException); // each loser got a 409

        // The database agrees: the seat is HELD by the winner's hold, and only one hold row survived.
        UUID winnerHold = outcomes.stream().filter(Outcome::won).findFirst().orElseThrow().holdId();
        Map<String, Object> row = jdbc.queryForMap("SELECT status, hold_id FROM event_seats WHERE id = ?", seat);
        assertThat(row.get("status")).isEqualTo("HELD");
        assertThat(row.get("hold_id")).isEqualTo(winnerHold);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holds WHERE event_id = ?", Long.class, eventId))
                .isEqualTo(1); // the 49 losing transactions rolled back their hold rows too
    }

    @Test
    void overlappingMultiSeatHoldsNeverShareASeatAndAreAllOrNothing() throws Exception {
        List<Long> users = createUsers(40);
        Random random = new Random(42); // fixed seed: the same overlapping requests every run
        List<Callable<Outcome>> attempts = new ArrayList<>();
        for (long userId : users) {
            int size = 2 + random.nextInt(3);                    // 2-4 seats
            int start = random.nextInt(12 - size + 1);           // squeezed into the first 12 seats
            List<Long> wanted = new ArrayList<>(seatIds.subList(start, start + size));
            if (random.nextBoolean()) {
                java.util.Collections.reverse(wanted);           // vary lock order too
            }
            attempts.add(() -> attempt(userId, wanted));
        }

        List<Outcome> outcomes = runAllAtOnce(attempts);
        List<Outcome> winners = outcomes.stream().filter(Outcome::won).toList();

        assertThat(winners).isNotEmpty();
        // Losers failed cleanly: a 409 (seat taken) or a 409 from deadlock detection, never anything else.
        assertThat(outcomes).filteredOn(o -> !o.won()).allMatch(o ->
                o.error() instanceof SeatsUnavailableException || o.error() instanceof PessimisticLockingFailureException);

        // No seat is claimed by two winners.
        Set<Long> claimed = new HashSet<>();
        for (Outcome winner : winners) {
            for (long seat : winner.seats()) {
                assertThat(claimed.add(seat)).as("seat %d held twice", seat).isTrue();
            }
        }
        // Every winner really owns ALL of its seats in the database...
        for (Outcome winner : winners) {
            List<UUID> owners = jdbc.queryForList("SELECT hold_id FROM event_seats WHERE id IN (%s)"
                    .formatted(join(winner.seats())), UUID.class);
            assertThat(owners).containsOnly(winner.holdId());
        }
        // ...and losers own NONE (no half-held requests): only winners' holds exist.
        Set<UUID> holdRows = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM holds WHERE event_id = ?", UUID.class, eventId));
        assertThat(holdRows).isEqualTo(winners.stream().map(Outcome::holdId).collect(Collectors.toSet()));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM event_seats WHERE event_id = ? AND status = 'HELD'", Long.class, eventId))
                .isEqualTo(claimed.size());
    }

    @Test
    void expiredHoldsSeatsCanBeHeldByAnotherUser() {
        List<Long> users = createUsers(2);
        long seat = seatIds.getFirst();
        UUID firstHold = holdService.createHold(users.get(0), eventId, List.of(seat)).holdId();
        expire(firstHold); // pretend 5 minutes passed; the cleanup job has NOT run

        UUID secondHold = holdService.createHold(users.get(1), eventId, List.of(seat)).holdId();

        assertThat(jdbc.queryForObject("SELECT hold_id FROM event_seats WHERE id = ?", UUID.class, seat))
                .isEqualTo(secondHold);
    }

    @Test
    void failedHoldLeavesNoneOfItsSeatsHeld() {
        List<Long> users = createUsers(2);
        long taken = seatIds.get(0);
        long free = seatIds.get(1);
        holdService.createHold(users.get(0), eventId, List.of(taken));

        Outcome second = attempt(users.get(1), List.of(free, taken));

        assertThat(second.error()).isInstanceOf(SeatsUnavailableException.class);
        // The UPDATE did claim `free` before the count check failed; the rollback must undo that.
        assertThat(seatStatus(free)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holds WHERE user_id = ?", Long.class, users.get(1)))
                .isZero();
    }

    @Test
    void activeHoldCannotBeTakenOver() {
        List<Long> users = createUsers(2);
        long seat = seatIds.getFirst();
        holdService.createHold(users.get(0), eventId, List.of(seat));

        Outcome second = attempt(users.get(1), List.of(seat));

        assertThat(second.error()).isInstanceOf(SeatsUnavailableException.class);
    }

    @Test
    void cleanupJobFreesExpiredSeatsAndLeavesActiveOnesAlone() {
        List<Long> users = createUsers(2);
        UUID expiredHold = holdService.createHold(users.get(0), eventId, List.of(seatIds.get(0))).holdId();
        UUID activeHold = holdService.createHold(users.get(1), eventId, List.of(seatIds.get(1))).holdId();
        expire(expiredHold);

        expiryJob.releaseExpiredHolds();

        assertThat(seatStatus(seatIds.get(0))).isEqualTo("AVAILABLE");
        assertThat(holdStatus(expiredHold)).isEqualTo("EXPIRED");
        assertThat(seatStatus(seatIds.get(1))).isEqualTo("HELD");
        assertThat(holdStatus(activeHold)).isEqualTo("ACTIVE");
    }

    // ---- helpers ---------------------------------------------------------

    private record Outcome(UUID holdId, List<Long> seats, Exception error) {
        boolean won() {
            return error == null;
        }
    }

    private Outcome attempt(long userId, List<Long> wanted) {
        try {
            return new Outcome(holdService.createHold(userId, eventId, wanted).holdId(), wanted, null);
        } catch (Exception e) {
            return new Outcome(null, wanted, e);
        }
    }

    /**
     * Starts every task on its own thread, then releases them all at the same instant with a latch,
     * to maximise contention. (They still queue for the 10 pooled DB connections, like real traffic.)
     */
    private static <T> List<T> runAllAtOnce(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<Long> createUsers(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(createUser(Role.USER).getId());
        }
        return ids;
    }

    private void expire(UUID holdId) {
        jdbc.update("UPDATE holds SET expires_at = now() - interval '1 second' WHERE id = ?", holdId);
        jdbc.update("UPDATE event_seats SET held_until = now() - interval '1 second' WHERE hold_id = ?", holdId);
    }

    private String seatStatus(long seatId) {
        return jdbc.queryForObject("SELECT status FROM event_seats WHERE id = ?", String.class, seatId);
    }

    private String holdStatus(UUID holdId) {
        return jdbc.queryForObject("SELECT status FROM holds WHERE id = ?", String.class, holdId);
    }

    private static String join(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
