package com.firstrow.observability;

import com.firstrow.AbstractIntegrationTest;
import com.firstrow.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
class ObservabilityIntegrationTest extends AbstractIntegrationTest {

    @Test
    void everyResponseGetsARequestId() throws Exception {
        mockMvc.perform(get("/api/v1/events"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void incomingRequestIdIsEchoedInHeaderAndErrorBody() throws Exception {
        mockMvc.perform(get("/api/v1/events/999999").header("X-Request-Id", "trace-me-123"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "trace-me-123"))
                .andExpect(jsonPath("$.requestId").value("trace-me-123"));
    }

    @Test
    void securityErrorsCarryTheRequestIdToo() throws Exception {
        mockMvc.perform(post("/api/v1/admin/venues").header("X-Request-Id", "sec-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.requestId").value("sec-1"));
    }

    @Test
    void holdMetricsCountSuccessesAndConflictsAndTimeBoth() throws Exception {
        long eventId = jdbc.queryForObject("SELECT id FROM events WHERE title = 'Starfall — Premiere Night'", Long.class);
        long seat = jdbc.queryForObject("SELECT min(id) FROM event_seats WHERE event_id = ?", Long.class, eventId);
        double createdBefore = metric("holds_granted_total");
        double conflictsBefore = metric("holds_conflicts_total");
        double timedBefore = metric("holds_create_seconds_count");

        hold(eventId, seat).andExpect(status().isCreated());
        hold(eventId, seat).andExpect(status().isConflict());

        assertThat(metric("holds_granted_total")).isEqualTo(createdBefore + 1);
        assertThat(metric("holds_conflicts_total")).isEqualTo(conflictsBefore + 1);
        assertThat(metric("holds_create_seconds_count")).isEqualTo(timedBefore + 2);
    }

    @Test
    void prometheusEndpointHasHistogramsForP95AndTheApplicationTag() throws Exception {
        mockMvc.perform(get("/api/v1/events")).andExpect(status().isOk());

        String scrape = scrape();
        assertThat(scrape).contains("http_server_requests_seconds_bucket{");
        assertThat(scrape).contains("holds_create_seconds_bucket{");
        assertThat(scrape).contains("application=\"firstrow\"");
        // Each name must start a line (not just appear in a "# HELP" comment) and be the exact exported name.
        assertThat(scrape).contains("\nbookings_confirmed_total{", "\nbookings_cancelled_total{",
                "\nholds_expired_total{", "\nholds_granted_total{", "\nholds_conflicts_total{");
    }

    private org.springframework.test.web.servlet.ResultActions hold(long eventId, long seatId) throws Exception {
        return mockMvc.perform(post("/api/v1/events/{id}/holds", eventId)
                .header("Authorization", bearerTokenFor(Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("seatIds", List.of(seatId)))));
    }

    private String scrape() throws Exception {
        return mockMvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
    }

    /** Value of a single-series metric from the Prometheus text format, e.g. holds_created_total{application="firstrow"} 3.0 */
    private double metric(String name) throws Exception {
        Matcher m = Pattern.compile("(?m)^" + name + "\\{[^}]*} ([0-9.eE+-]+)$").matcher(scrape());
        assertThat(m.find()).as("metric %s present", name).isTrue();
        return Double.parseDouble(m.group(1));
    }
}
