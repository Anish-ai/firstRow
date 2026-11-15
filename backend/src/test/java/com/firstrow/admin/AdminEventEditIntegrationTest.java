package com.firstrow.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstrow.AbstractIntegrationTest;
import com.firstrow.PrivateEvent;
import com.firstrow.user.Role;
import com.firstrow.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin editing and cancelling events. Not @Transactional: cancellation must really commit. */
class AdminEventEditIntegrationTest extends AbstractIntegrationTest {

    private PrivateEvent event;
    private String admin;
    private long venueId;

    @BeforeEach
    void setUp() {
        event = PrivateEvent.create(jdbc, 10);
        admin = bearerTokenFor(Role.ADMIN);
        venueId = jdbc.queryForObject("SELECT max(id) FROM venues", Long.class);
    }

    @AfterEach
    void tearDown() {
        event.delete();
    }

    @Test
    void adminEditsTitleTypeTimeAndVenueAndTheVersionGoesUp() throws Exception {
        Instant newStart = Instant.now().plus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        update(admin, Map.of("title", "  Renamed Final  ", "type", "PREMIERE", "startsAt", newStart.toString(),
                "venueId", venueId, "version", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed Final"))
                .andExpect(jsonPath("$.type").value("PREMIERE"))
                .andExpect(jsonPath("$.startsAt").value(newStart.toString()))
                .andExpect(jsonPath("$.venue.id").value(venueId))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.totalSeats").value(10));

        mockMvc.perform(get("/api/v1/events/{id}", event.id()))
                .andExpect(jsonPath("$.title").value("Renamed Final"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void editBasedOnAnOldVersionIs409AndChangesNothing() throws Exception {
        update(admin, body("First edit", 0)).andExpect(status().isOk());

        update(admin, body("Second admin, stale copy", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This event was changed by someone else. Reload it and try again."));

        assertThat(title()).isEqualTo("First edit");
    }

    @Test
    void invalidEditsAreRejected() throws Exception {
        Map<String, Object> past = body("Past", 0);
        past.put("startsAt", Instant.now().minus(1, ChronoUnit.DAYS).toString());
        update(admin, past).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.startsAt").exists());

        Map<String, Object> noVenue = body("No venue", 0);
        noVenue.put("venueId", 999_999);
        update(admin, noVenue).andExpect(status().isBadRequest());

        update(bearerTokenFor(Role.USER), body("Not an admin", 0)).andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/admin/events/{id}", 999_999).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body("Missing", 0))))
                .andExpect(status().isNotFound());
        assertThat(title()).isEqualTo("Private Test Match");
    }

    @Test
    void cancellingCancelsBookingsReleasesHoldsAndStopsNewHolds() throws Exception {
        List<Long> seats = event.seatIds();
        User booker = createUser(Role.USER);
        User holder = createUser(Role.USER);
        UUID bookedHold = holdId(hold(booker, seats.get(0)));
        mockMvc.perform(post("/api/v1/bookings").header("Authorization", bearerToken(booker))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("holdId", bookedHold.toString()))))
                .andExpect(status().isCreated());
        UUID activeHold = holdId(hold(holder, seats.get(1)));

        cancel(admin).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE hold_id = ?", String.class, bookedHold))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM holds WHERE id = ?", String.class, activeHold))
                .isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_seats WHERE event_id = ? AND status <> 'AVAILABLE'",
                Long.class, event.id())).isZero();

        hold(createUser(Role.USER), seats.get(2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This event is not open for booking"));
        update(admin, body("Too late", 1)).andExpect(status().isConflict());
        cancel(admin).andExpect(status().isOk()); // cancelling twice is harmless
        mockMvc.perform(get("/api/v1/bookings/me").header("Authorization", bearerToken(booker)))
                .andExpect(jsonPath("$[0].status").value("CANCELLED"));
    }

    @Test
    void aHoldMadeBeforeCancellationCannotBeBookedAfterIt() throws Exception {
        User user = createUser(Role.USER);
        UUID holdId = holdId(hold(user, event.seatIds().get(0)));
        // Simulate the race where the hold survived: the event is cancelled but this hold is still ACTIVE.
        jdbc.update("UPDATE events SET status = 'CANCELLED' WHERE id = ?", event.id());

        mockMvc.perform(post("/api/v1/bookings").header("Authorization", bearerToken(user))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("holdId", holdId.toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This event has been cancelled, so it can't be booked."));
    }

    // ---- helpers ---------------------------------------------------------

    private Map<String, Object> body(String title, long version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("type", "MATCH");
        body.put("startsAt", Instant.now().plus(40, ChronoUnit.DAYS).toString());
        body.put("venueId", venueId);
        body.put("version", version);
        return body;
    }

    private ResultActions update(String token, Map<String, ?> body) throws Exception {
        return mockMvc.perform(put("/api/v1/admin/events/{id}", event.id())
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    private ResultActions cancel(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/events/{id}/cancel", event.id()).header("Authorization", token));
    }

    private ResultActions hold(User user, long seatId) throws Exception {
        return mockMvc.perform(post("/api/v1/events/{id}/holds", event.id())
                .header("Authorization", bearerToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("seatIds", List.of(seatId)))));
    }

    private UUID holdId(ResultActions result) throws Exception {
        JsonNode body = objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
        return UUID.fromString(body.get("holdId").asText());
    }

    private String title() {
        return jdbc.queryForObject("SELECT title FROM events WHERE id = ?", String.class, event.id());
    }
}
