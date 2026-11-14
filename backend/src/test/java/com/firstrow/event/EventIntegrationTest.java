package com.firstrow.event;

import com.firstrow.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs against the Flyway seed data: 2 premieres (84 seats each) and 2 matches (400 seats each).
 * @Transactional rolls back the holds these tests insert.
 */
@Transactional
class EventIntegrationTest extends AbstractIntegrationTest {

    @Test
    void listsUpcomingEventsSoonestFirstWithoutLogin() throws Exception {
        mockMvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content", hasSize(4)))
                // Seeded "Desert Falcons" match starts first (in 7 days).
                .andExpect(jsonPath("$.content[0].title").value("Desert Falcons vs Coastal Kings — Semi-final"))
                .andExpect(jsonPath("$.content[0].venue.city").value("Bengaluru"));
    }

    @Test
    void filtersByTypeAndPaginates() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("type", "PREMIERE").param("size", "1").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].type").value("PREMIERE"));
    }

    @Test
    void fromFiltersOutEarlierEvents() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("from", "2999-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void invalidQueryParametersAre400() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("type", "CONCERT"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(get("/api/v1/events").param("size", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void eventDetailIncludesSeatCounts() throws Exception {
        long eventId = eventIdByTitle("Starfall — Premiere Night");

        mockMvc.perform(get("/api/v1/events/{id}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Starfall — Premiere Night"))
                .andExpect(jsonPath("$.venue.name").value("Aurora Grand Cinema"))
                .andExpect(jsonPath("$.totalSeats").value(84))
                .andExpect(jsonPath("$.availableSeats").value(84));
    }

    @Test
    void unknownEventIs404ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/events/999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Event 999999 not found"));
        mockMvc.perform(get("/api/v1/events/999999/seats"))
                .andExpect(status().isNotFound());
    }

    @Test
    void seatMapListsEverySeatInOrder() throws Exception {
        long eventId = eventIdByTitle("Starfall — Premiere Night");

        mockMvc.perform(get("/api/v1/events/{id}/seats", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.seats", hasSize(84)))
                .andExpect(jsonPath("$.seats[0].section").value("A"))
                .andExpect(jsonPath("$.seats[0].row").value("A"))
                .andExpect(jsonPath("$.seats[0].number").value(1))
                .andExpect(jsonPath("$.seats[0].priceTier").value("RECLINER"))
                .andExpect(jsonPath("$.seats[0].pricePaise").value(75000))
                .andExpect(jsonPath("$.seats[*].status", everyItem(is("AVAILABLE"))));
    }

    @Test
    void lazyExpiryShowsSeatWithExpiredHoldAsAvailable() throws Exception {
        long eventId = eventIdByTitle("Starfall — Premiere Night");
        long activeSeat = seatId(eventId, "A", 1);
        long expiredSeat = seatId(eventId, "A", 2);
        // No cleanup job has run: the expired seat is still stored as HELD in the table.
        holdSeat(eventId, activeSeat, "now() + interval '5 minutes'");
        holdSeat(eventId, expiredSeat, "now() - interval '1 second'");

        mockMvc.perform(get("/api/v1/events/{id}/seats", eventId))
                .andExpect(jsonPath("$.seats[?(@.id == %d)].status".formatted(activeSeat)).value("HELD"))
                .andExpect(jsonPath("$.seats[?(@.id == %d)].status".formatted(expiredSeat)).value("AVAILABLE"));

        mockMvc.perform(get("/api/v1/events/{id}", eventId))
                .andExpect(jsonPath("$.availableSeats").value(83));
    }

    // ---- helpers ---------------------------------------------------------

    private long eventIdByTitle(String title) {
        return jdbc.queryForObject("SELECT id FROM events WHERE title = ?", Long.class, title);
    }

    private long seatId(long eventId, String row, int number) {
        return jdbc.queryForObject("""
                SELECT id FROM event_seats WHERE event_id = ? AND section = 'A' AND row_label = ? AND seat_number = ?
                """, Long.class, eventId, row, number);
    }

    /** Writes a hold directly with SQL; the holds API arrives in phase 3. */
    private void holdSeat(long eventId, long seatId, String heldUntilSql) {
        long userId = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash) VALUES (?, 'x') RETURNING id",
                Long.class, UUID.randomUUID() + "@example.com");
        UUID holdId = UUID.randomUUID();
        jdbc.update("INSERT INTO holds (id, user_id, event_id, expires_at) VALUES (?, ?, ?, " + heldUntilSql + ")",
                holdId, userId, eventId);
        jdbc.update("UPDATE event_seats SET status = 'HELD', hold_id = ?, held_until = " + heldUntilSql
                + " WHERE id = ?", holdId, seatId);
    }
}
