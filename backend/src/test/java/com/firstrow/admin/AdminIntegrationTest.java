package com.firstrow.admin;

import com.firstrow.AbstractIntegrationTest;
import com.firstrow.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Covers both the admin features and the security rules (401 / 403 / allowed). */
@Transactional
class AdminIntegrationTest extends AbstractIntegrationTest {

    @Test
    void withoutTokenIs401ProblemDetail() throws Exception {
        createVenue(null)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void tamperedTokenIs401() throws Exception {
        String token = bearerTokenFor(Role.ADMIN);
        String tampered = token.substring(0, token.length() - 4) + "AAAA"; // breaks the signature

        createVenue(tampered).andExpect(status().isUnauthorized());
    }

    @Test
    void regularUserIs403ProblemDetail() throws Exception {
        createVenue(bearerTokenFor(Role.USER))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void adminCreatesVenue() throws Exception {
        createVenue(bearerTokenFor(Role.ADMIN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Moonlight Dome"))
                .andExpect(jsonPath("$.city").value("Pune"));
    }

    @Test
    void adminCreatesEventWithAllSeatsInOneGo() throws Exception {
        String admin = bearerTokenFor(Role.ADMIN);
        long venueId = jdbc.queryForObject("SELECT min(id) FROM venues", Long.class);

        String location = createEvent(admin, eventRequest(venueId, List.of(
                section("A", 3, 10, tier("RECLINER", 80_000, 1, 1), tier("GOLD", 50_000, 2, 3)),
                section("B", 2, 8, tier("SILVER", 30_000, 1, 2)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Midnight Lanterns — Premiere Night"))
                .andExpect(jsonPath("$.totalSeats").value(3 * 10 + 2 * 8))
                .andExpect(jsonPath("$.availableSeats").value(46))
                .andReturn().getResponse().getHeader("Location");

        long eventId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_seats WHERE event_id = ?", Long.class, eventId))
                .isEqualTo(46);
        assertThat(jdbc.queryForObject("""
                SELECT price_paise FROM event_seats
                WHERE event_id = ? AND section = 'A' AND row_label = 'A' AND seat_number = 1
                """, Long.class, eventId)).isEqualTo(80_000);
    }

    @Test
    void invalidLayoutCreatesNothing() throws Exception {
        String admin = bearerTokenFor(Role.ADMIN);
        long venueId = jdbc.queryForObject("SELECT min(id) FROM venues", Long.class);
        long eventsBefore = jdbc.queryForObject("SELECT count(*) FROM events", Long.class);

        createEvent(admin, eventRequest(venueId, List.of(section("A", 3, 10, tier("GOLD", 50_000, 1, 2)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Section A: row C has no price tier"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM events", Long.class)).isEqualTo(eventsBefore);
    }

    @Test
    void unknownVenueIs400() throws Exception {
        createEvent(bearerTokenFor(Role.ADMIN),
                eventRequest(999_999L, List.of(section("A", 1, 5, tier("GOLD", 50_000, 1, 1)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Venue 999999 does not exist"));
    }

    @Test
    void eventInThePastFailsValidation() throws Exception {
        Map<String, Object> request = new java.util.HashMap<>(eventRequest(1L,
                List.of(section("A", 1, 5, tier("GOLD", 50_000, 1, 1)))));
        request.put("startsAt", "2020-01-01T18:00:00Z");

        createEvent(bearerTokenFor(Role.ADMIN), request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.startsAt").exists());
    }

    // ---- helpers ---------------------------------------------------------

    private ResultActions createVenue(String bearerToken) throws Exception {
        var request = post("/api/v1/admin/venues")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Moonlight Dome", "city", "Pune")));
        if (bearerToken != null) {
            request.header("Authorization", bearerToken);
        }
        return mockMvc.perform(request);
    }

    private ResultActions createEvent(String bearerToken, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/events")
                .header("Authorization", bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    private static Map<String, Object> eventRequest(long venueId, List<Map<String, Object>> sections) {
        return Map.of(
                "venueId", venueId,
                "title", "Midnight Lanterns — Premiere Night",
                "type", "PREMIERE",
                "startsAt", Instant.now().plus(30, ChronoUnit.DAYS).toString(),
                "layout", Map.of("sections", sections));
    }

    @SafeVarargs
    private static Map<String, Object> section(String name, int rows, int seatsPerRow, Map<String, Object>... tiers) {
        return Map.of("name", name, "rows", rows, "seatsPerRow", seatsPerRow, "tiers", List.of(tiers));
    }

    private static Map<String, Object> tier(String name, long pricePaise, int fromRow, int toRow) {
        return Map.of("name", name, "pricePaise", pricePaise, "fromRow", fromRow, "toRow", toRow);
    }
}
