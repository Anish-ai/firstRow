package com.firstrow.hold;

import com.firstrow.AbstractIntegrationTest;
import com.firstrow.user.Role;
import com.firstrow.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract of the holds API. @Transactional rolls back every hold made here. */
@Transactional
class HoldApiIntegrationTest extends AbstractIntegrationTest {

    private long eventId;
    private List<Long> seats;

    @BeforeEach
    void pickSeedEvent() {
        eventId = jdbc.queryForObject("SELECT id FROM events WHERE title = 'Starfall — Premiere Night'", Long.class);
        // Row A is RECLINER (₹750), row C is GOLD (₹450).
        seats = jdbc.queryForList("""
                SELECT id FROM event_seats WHERE event_id = ? AND row_label IN ('A', 'C')
                ORDER BY row_label, seat_number
                """, Long.class, eventId);
    }

    @Test
    void holdReturnsSeatsTotalAndExpiry() throws Exception {
        long recliner = seats.get(0);
        long gold = seats.get(14);

        hold(bearerTokenFor(Role.USER), List.of(recliner, gold))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdId").isNotEmpty())
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.seats", hasSize(2)))
                .andExpect(jsonPath("$.totalPaise").value(75_000 + 45_000));

        mockMvc.perform(get("/api/v1/events/{id}/seats", eventId))
                .andExpect(jsonPath("$.seats[?(@.id == %d)].status".formatted(recliner)).value("HELD"));
    }

    /**
     * Only checks the 409 response. The "free seat is rolled back" half lives in
     * HoldConcurrencyIntegrationTest: in a @Transactional test the service joins the test's transaction,
     * so its rollback doesn't happen until the test ends and can't be observed here.
     */
    @Test
    void takenSeatIs409ListingOnlyTheTakenSeat() throws Exception {
        long taken = seats.get(0);
        long free = seats.get(1);
        hold(bearerTokenFor(Role.USER), List.of(taken)).andExpect(status().isCreated());

        hold(bearerTokenFor(Role.USER), List.of(free, taken))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.unavailableSeatIds", hasSize(1)))
                .andExpect(jsonPath("$.unavailableSeatIds[0]").value(taken));
    }

    @Test
    void holdingRequiresLogin() throws Exception {
        mockMvc.perform(post("/api/v1/events/{id}/holds", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("seatIds", List.of(seats.get(0))))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sevenSeatsIs400() throws Exception {
        hold(bearerTokenFor(Role.USER), seats.subList(0, 7))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("You can hold at most 6 seats at a time"));
    }

    @Test
    void emptySeatListIs400() throws Exception {
        hold(bearerTokenFor(Role.USER), List.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.seatIds").exists());
    }

    @Test
    void seatOfAnotherEventIs400() throws Exception {
        long otherEventSeat = jdbc.queryForObject(
                "SELECT min(id) FROM event_seats WHERE event_id <> ?", Long.class, eventId);

        hold(bearerTokenFor(Role.USER), List.of(otherEventSeat))
                .andExpect(status().isBadRequest());
    }

    @Test
    void releaseFreesSeatsAndOnlyOwnerCanRelease() throws Exception {
        User owner = createUser(Role.USER);
        long seat = seats.get(0);
        String holdId = objectMapper.readTree(hold(bearerToken(owner), List.of(seat))
                .andReturn().getResponse().getContentAsString()).get("holdId").asText();

        mockMvc.perform(delete("/api/v1/holds/{id}", holdId).header("Authorization", bearerTokenFor(Role.USER)))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/holds/{id}", holdId).header("Authorization", bearerToken(owner)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/events/{id}/seats", eventId))
                .andExpect(jsonPath("$.seats[?(@.id == %d)].status".formatted(seat)).value("AVAILABLE"));

        mockMvc.perform(delete("/api/v1/holds/{id}", holdId).header("Authorization", bearerToken(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Hold is already released"));
    }

    @Test
    void eleventhHoldInAMinuteIs429WithRetryAfter() throws Exception {
        String token = bearerTokenFor(Role.USER);
        for (int i = 0; i < 10; i++) {
            hold(token, List.of(seats.get(i))).andExpect(status().isCreated());
        }

        hold(token, List.of(seats.get(10)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private ResultActions hold(String bearerToken, List<Long> seatIds) throws Exception {
        return mockMvc.perform(post("/api/v1/events/{id}/holds", eventId)
                .header("Authorization", bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("seatIds", seatIds))));
    }
}
