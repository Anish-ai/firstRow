package com.firstrow.booking;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end booking flow over HTTP, NOT @Transactional: every request commits for real, so rollbacks,
 * idempotency and concurrency behave exactly as in production. Each test uses a private event.
 */
class BookingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BookingService bookingService;

    private PrivateEvent event;
    private List<Long> seats;
    private User alice;

    @BeforeEach
    void setUp() {
        event = PrivateEvent.create(jdbc, 10);
        seats = event.seatIds();
        alice = createUser(Role.USER);
    }

    @AfterEach
    void tearDown() {
        event.delete();
    }

    @Test
    void confirmBooksTheSeatsAndReturnsTheBooking() throws Exception {
        UUID hold = hold(alice, seats.get(0), seats.get(1));

        confirm(alice, hold, UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().doesNotExist(BookingController.REPLAYED_HEADER))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalPaise").value(2 * PrivateEvent.SEAT_PRICE_PAISE))
                .andExpect(jsonPath("$.holdId").value(hold.toString()))
                .andExpect(jsonPath("$.event.title").value("Private Test Match"))
                .andExpect(jsonPath("$.seats", hasSize(2)));

        assertThat(seatStatus(seats.get(0))).isEqualTo("BOOKED");
        assertThat(seatStatus(seats.get(1))).isEqualTo("BOOKED");
        assertThat(holdStatus(hold)).isEqualTo("CONVERTED");
        mockMvc.perform(get("/api/v1/events/{id}/seats", event.id()))
                .andExpect(jsonPath("$.seats[?(@.id == %d)].status".formatted(seats.get(0))).value("BOOKED"));
    }

    @Test
    void retryWithTheSameKeyReturnsTheOriginalBooking() throws Exception {
        UUID hold = hold(alice, seats.get(0));
        String key = UUID.randomUUID().toString();

        long firstId = idOf(confirm(alice, hold, key).andExpect(status().isCreated()));
        long secondId = idOf(confirm(alice, hold, key)
                .andExpect(status().isOk())
                .andExpect(header().string(BookingController.REPLAYED_HEADER, "true")));

        assertThat(secondId).isEqualTo(firstId);
        assertThat(bookingCount()).isEqualTo(1);
    }

    @Test
    void tenConcurrentConfirmsWithTheSameKeyCreateExactlyOneBooking() throws Exception {
        UUID hold = hold(alice, seats.get(0), seats.get(1));
        String key = UUID.randomUUID().toString();

        List<BookingService.ConfirmResult> results = runConcurrently(10,
                () -> bookingService.confirm(alice.getId(), hold, key));

        assertThat(results).extracting(r -> r.booking().id()).containsOnly(results.getFirst().booking().id());
        assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1); // one did the work, nine replayed it
        assertThat(bookingCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_seats bs JOIN bookings b ON b.id = bs.booking_id "
                + "WHERE b.event_id = ?", Long.class, event.id())).isEqualTo(2);
    }

    @Test
    void sameKeyForADifferentHoldIs422() throws Exception {
        String key = UUID.randomUUID().toString();
        confirm(alice, hold(alice, seats.get(0)), key).andExpect(status().isCreated());

        confirm(alice, hold(alice, seats.get(1)), key).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void bookedHoldCannotBeBookedAgainWithANewKey() throws Exception {
        UUID hold = hold(alice, seats.get(0));
        confirm(alice, hold, UUID.randomUUID().toString()).andExpect(status().isCreated());

        confirm(alice, hold, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This hold has already been booked"));
        assertThat(bookingCount()).isEqualTo(1);
    }

    @Test
    void expiredHoldCannotBeBooked() throws Exception {
        UUID hold = hold(alice, seats.get(0));
        jdbc.update("UPDATE holds SET expires_at = now() - interval '1 second' WHERE id = ?", hold);
        jdbc.update("UPDATE event_seats SET held_until = now() - interval '1 second' WHERE hold_id = ?", hold);

        confirm(alice, hold, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Your hold has expired. Please choose your seats again."));
        assertThat(bookingCount()).isZero();
    }

    @Test
    void seatLostAtTheLastMomentRollsBackEverything() throws Exception {
        UUID hold = hold(alice, seats.get(0), seats.get(1));
        // Simulate the rare race: the hold is still ACTIVE, but another hold grabbed one of its seats.
        UUID otherHold = hold(createUser(Role.USER), seats.get(5));
        jdbc.update("UPDATE event_seats SET hold_id = ? WHERE id = ?", otherHold, seats.get(1));

        confirm(alice, hold, UUID.randomUUID().toString()).andExpect(status().isConflict());

        // Rolled back: nothing booked, the hold is still ACTIVE, the remaining seat is still just HELD.
        assertThat(bookingCount()).isZero();
        assertThat(holdStatus(hold)).isEqualTo("ACTIVE");
        assertThat(seatStatus(seats.get(0))).isEqualTo("HELD");
    }

    @Test
    void otherUsersHoldIsNotFound() throws Exception {
        UUID hold = hold(alice, seats.get(0));

        confirm(createUser(Role.USER), hold, UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        UUID hold = hold(alice, seats.get(0));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", bearerToken(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("holdId", hold.toString()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void myBookingsListsOnlyMyBookingsNewestFirst() throws Exception {
        long older = idOf(confirm(alice, hold(alice, seats.get(0)), UUID.randomUUID().toString()));
        long newer = idOf(confirm(alice, hold(alice, seats.get(1), seats.get(2)), UUID.randomUUID().toString()));
        User bob = createUser(Role.USER);
        confirm(bob, hold(bob, seats.get(3)), UUID.randomUUID().toString()).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/bookings/me").header("Authorization", bearerToken(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(newer))
                .andExpect(jsonPath("$[0].seats", hasSize(2)))
                .andExpect(jsonPath("$[1].id").value(older))
                .andExpect(jsonPath("$[1].event.venueName").isNotEmpty());
    }

    @Test
    void cancelFreesTheSeatsForSomeoneElseToBook() throws Exception {
        long seat = seats.get(0);
        long bookingId = idOf(confirm(alice, hold(alice, seat), UUID.randomUUID().toString()));
        User bob = createUser(Role.USER);

        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", bookingId).header("Authorization", bearerToken(bob)))
                .andExpect(status().isNotFound()); // not Bob's booking

        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", bookingId).header("Authorization", bearerToken(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(seatStatus(seat)).isEqualTo("AVAILABLE");

        // Bob can now hold and book the same seat: the partial unique index ignores the cancelled booking.
        confirm(bob, hold(bob, seat), UUID.randomUUID().toString()).andExpect(status().isCreated());
        assertThat(seatStatus(seat)).isEqualTo("BOOKED");

        // Cancelling again is harmless and does NOT free Bob's seat.
        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", bookingId).header("Authorization", bearerToken(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(seatStatus(seat)).isEqualTo("BOOKED");
    }

    // ---- helpers ---------------------------------------------------------

    private UUID hold(User user, Long... seatIds) throws Exception {
        String body = mockMvc.perform(post("/api/v1/events/{id}/holds", event.id())
                        .header("Authorization", bearerToken(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("seatIds", List.of(seatIds)))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("holdId").asText());
    }

    private ResultActions confirm(User user, UUID holdId, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/bookings")
                .header("Authorization", bearerToken(user))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("holdId", holdId.toString()))));
    }

    private long idOf(ResultActions result) throws Exception {
        JsonNode body = objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
        return body.get("id").asLong();
    }

    private long bookingCount() {
        return jdbc.queryForObject("SELECT count(*) FROM bookings WHERE event_id = ?", Long.class, event.id());
    }

    private String seatStatus(long seatId) {
        return jdbc.queryForObject("SELECT status FROM event_seats WHERE id = ?", String.class, seatId);
    }

    private String holdStatus(UUID holdId) {
        return jdbc.queryForObject("SELECT status FROM holds WHERE id = ?", String.class, holdId);
    }

    private static <T> List<T> runConcurrently(int threads, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
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
