package com.firstrow.hold;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstrow.AbstractIntegrationTest;
import com.firstrow.PrivateEvent;
import com.firstrow.user.Role;
import com.firstrow.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "One active hold per user per event, editable, max 6 seats per event" over HTTP against real Postgres.
 * NOT @Transactional: requests commit for real, so unique-index waits and rollbacks behave as in production.
 */
class HoldEditIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private HoldService holdService;

    private PrivateEvent event;
    private List<Long> seats;
    private User alice;

    @BeforeEach
    void setUp() {
        event = PrivateEvent.create(jdbc, 20);
        seats = event.seatIds();
        alice = createUser(Role.USER);
    }

    @AfterEach
    void tearDown() {
        event.delete();
    }

    // ---- one active hold per user per event ------------------------------

    @Test
    void secondHoldForTheSameEventIs409AndTheFirstHoldIsUntouched() throws Exception {
        UUID first = holdId(hold(alice, seats.get(0), seats.get(1)).andExpect(status().isCreated()));

        hold(alice, seats.get(2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "You already have seats on hold for this event. Change or release that hold instead."));

        assertThat(activeHolds(alice)).isEqualTo(1);
        assertThat(seatHolder(seats.get(0))).isEqualTo(first);
        assertThat(seatStatus(seats.get(2))).isEqualTo("AVAILABLE");
    }

    @Test
    void tenSimultaneousHoldsFromOneUserCreateExactlyOneHold() throws Exception {
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long seat = seats.get(i);
            tasks.add(() -> {
                try {
                    holdService.createHold(alice.getId(), event.id(), List.of(seat));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            });
        }

        List<Boolean> won = runAllAtOnce(tasks);

        assertThat(won).filteredOn(w -> w).hasSize(1);
        assertThat(activeHolds(alice)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_seats WHERE event_id = ? AND status = 'HELD'",
                Long.class, event.id())).isEqualTo(1);
    }

    @Test
    void anExpiredHoldDoesNotBlockANewOne() throws Exception {
        UUID old = holdId(hold(alice, seats.get(0)));
        jdbc.update("UPDATE holds SET expires_at = now() - interval '1 second' WHERE id = ?", old);
        jdbc.update("UPDATE event_seats SET held_until = now() - interval '1 second' WHERE hold_id = ?", old);

        hold(alice, seats.get(1)).andExpect(status().isCreated());

        assertThat(holdStatus(old)).isEqualTo("EXPIRED");
        assertThat(seatStatus(seats.get(0))).isEqualTo("AVAILABLE");
    }

    @Test
    void anotherUserCanStillHoldOtherSeats() throws Exception {
        hold(alice, seats.get(0)).andExpect(status().isCreated());

        hold(createUser(Role.USER), seats.get(1)).andExpect(status().isCreated());
    }

    // ---- editing a hold --------------------------------------------------

    @Test
    void editSwapsSeatsKeepsTheExpiryAndUpdatesTheTotal() throws Exception {
        JsonNode created = body(hold(alice, seats.get(0), seats.get(1), seats.get(2)));
        UUID holdId = UUID.fromString(created.get("holdId").asText());

        JsonNode edited = body(edit(alice, holdId, seats.get(1), seats.get(2), seats.get(3), seats.get(4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats", hasSize(4)))
                .andExpect(jsonPath("$.totalPaise").value(4 * PrivateEvent.SEAT_PRICE_PAISE)));

        assertThat(edited.get("expiresAt").asText()).isEqualTo(created.get("expiresAt").asText());
        assertThat(seatStatus(seats.get(0))).isEqualTo("AVAILABLE");
        assertThat(seatHolder(seats.get(4))).isEqualTo(holdId);
        assertThat(jdbc.queryForObject("SELECT seat_count FROM holds WHERE id = ?", Integer.class, holdId)).isEqualTo(4);
    }

    @Test
    void editWithATakenSeatIs409AndLeavesTheHoldExactlyAsItWas() throws Exception {
        hold(createUser(Role.USER), seats.get(5)).andExpect(status().isCreated());
        UUID holdId = holdId(hold(alice, seats.get(0), seats.get(1)));

        edit(alice, holdId, seats.get(0), seats.get(5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.unavailableSeatIds[0]").value(seats.get(5)));

        // Rolled back: seat 1 (which the edit freed before failing) is still ours.
        assertThat(seatHolder(seats.get(1))).isEqualTo(holdId);
        assertThat(jdbc.queryForObject("SELECT seat_count FROM holds WHERE id = ?", Integer.class, holdId)).isEqualTo(2);
    }

    @Test
    void confirmAfterAnEditBooksExactlyTheNewSeats() throws Exception {
        UUID holdId = holdId(hold(alice, seats.get(0), seats.get(1)));
        edit(alice, holdId, seats.get(2)).andExpect(status().isOk());

        confirm(alice, holdId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seats", hasSize(1)))
                .andExpect(jsonPath("$.seats[0].id").value(seats.get(2)))
                .andExpect(jsonPath("$.totalPaise").value(PrivateEvent.SEAT_PRICE_PAISE));
        assertThat(seatStatus(seats.get(0))).isEqualTo("AVAILABLE");
    }

    @Test
    void editingWithAnEmptyListReleasesTheHold() throws Exception {
        UUID holdId = holdId(hold(alice, seats.get(0)));

        edit(alice, holdId).andExpect(status().isNoContent());

        assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
        assertThat(seatStatus(seats.get(0))).isEqualTo("AVAILABLE");
    }

    @Test
    void editRulesSevenSeatsSomeoneElsesHoldAndABookedHold() throws Exception {
        UUID holdId = holdId(hold(alice, seats.get(0)));

        edit(alice, holdId, seats.subList(0, 7).toArray(Long[]::new)).andExpect(status().isBadRequest());
        edit(createUser(Role.USER), holdId, seats.get(1)).andExpect(status().isNotFound());

        confirm(alice, holdId).andExpect(status().isCreated());
        edit(alice, holdId, seats.get(1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This hold has already been booked"));
    }

    // ---- max 6 seats per user per event (booked + held) --------------------

    @Test
    void bookedSeatsCountTowardsTheSixSeatLimit() throws Exception {
        UUID first = holdId(hold(alice, seats.get(0), seats.get(1), seats.get(2), seats.get(3)));
        confirm(alice, first).andExpect(status().isCreated());

        hold(alice, seats.get(4), seats.get(5), seats.get(6))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "You can have at most 6 seats per event. You've already booked 4, so you can choose 2 more."));
        assertThat(seatStatus(seats.get(4))).isEqualTo("AVAILABLE");
        assertThat(activeHolds(alice)).isZero();

        UUID second = holdId(hold(alice, seats.get(4), seats.get(5)).andExpect(status().isCreated()));
        edit(alice, second, seats.get(4), seats.get(5), seats.get(6)).andExpect(status().isConflict());
    }

    @Test
    void cancelledBookingsDoNotCountTowardsTheLimit() throws Exception {
        UUID first = holdId(hold(alice, seats.get(0), seats.get(1), seats.get(2), seats.get(3), seats.get(4), seats.get(5)));
        long bookingId = body(confirm(alice, first)).get("id").asLong();
        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", bookingId).header("Authorization", bearerToken(alice)))
                .andExpect(status().isOk());

        hold(alice, seats.get(6), seats.get(7), seats.get(8), seats.get(9), seats.get(10), seats.get(11))
                .andExpect(status().isCreated());
    }

    // ---- reading your hold -----------------------------------------------

    @Test
    void myHoldForAnEventAndHoldById() throws Exception {
        mockMvc.perform(get("/api/v1/holds/me").param("eventId", String.valueOf(event.id()))
                        .header("Authorization", bearerToken(alice)))
                .andExpect(status().isNoContent());

        UUID holdId = holdId(hold(alice, seats.get(0), seats.get(1)));

        mockMvc.perform(get("/api/v1/holds/me").param("eventId", String.valueOf(event.id()))
                        .header("Authorization", bearerToken(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(holdId.toString()))
                .andExpect(jsonPath("$.seats", hasSize(2)));
        mockMvc.perform(get("/api/v1/holds/{id}", holdId).header("Authorization", bearerToken(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPaise").value(2 * PrivateEvent.SEAT_PRICE_PAISE));
        mockMvc.perform(get("/api/v1/holds/{id}", holdId).header("Authorization", bearerTokenFor(Role.USER)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/holds/me").param("eventId", String.valueOf(event.id())))
                .andExpect(status().isUnauthorized());

        confirm(alice, holdId).andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/holds/{id}", holdId).header("Authorization", bearerToken(alice)))
                .andExpect(status().isNotFound());
    }

    /**
     * The browser sends a CORS "preflight" before a cross-origin PUT/DELETE. Without PUT in the allowed
     * methods, editing a hold works in tests (no browser) but fails in the real app; a browser test caught that.
     */
    @Test
    void browsersMayCallEveryMethodTheFrontendUses() throws Exception {
        for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
            mockMvc.perform(options("/api/v1/holds/{id}/seats", UUID.randomUUID())
                            .header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", method)
                            .header("Access-Control-Request-Headers", "authorization,content-type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        }
    }

    // ---- helpers ---------------------------------------------------------

    private ResultActions hold(User user, Long... seatIds) throws Exception {
        return mockMvc.perform(post("/api/v1/events/{id}/holds", event.id())
                .header("Authorization", bearerToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("seatIds", List.of(seatIds)))));
    }

    private ResultActions edit(User user, UUID holdId, Long... seatIds) throws Exception {
        return mockMvc.perform(put("/api/v1/holds/{id}/seats", holdId)
                .header("Authorization", bearerToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("seatIds", List.of(seatIds)))));
    }

    private ResultActions confirm(User user, UUID holdId) throws Exception {
        return mockMvc.perform(post("/api/v1/bookings")
                .header("Authorization", bearerToken(user))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("holdId", holdId.toString()))));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private UUID holdId(ResultActions result) throws Exception {
        return UUID.fromString(body(result).get("holdId").asText());
    }

    private long activeHolds(User user) {
        return jdbc.queryForObject("SELECT count(*) FROM holds WHERE user_id = ? AND event_id = ? AND status = 'ACTIVE'",
                Long.class, user.getId(), event.id());
    }

    private String seatStatus(long seatId) {
        return jdbc.queryForObject("SELECT status FROM event_seats WHERE id = ?", String.class, seatId);
    }

    private UUID seatHolder(long seatId) {
        return jdbc.queryForObject("SELECT hold_id FROM event_seats WHERE id = ?", UUID.class, seatId);
    }

    private String holdStatus(UUID holdId) {
        return jdbc.queryForObject("SELECT status FROM holds WHERE id = ?", String.class, holdId);
    }

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
}
