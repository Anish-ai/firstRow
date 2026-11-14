package com.firstrow.event;

import com.firstrow.common.PageResponse;
import com.firstrow.event.EventDtos.EventDetail;
import com.firstrow.event.EventDtos.EventSummary;
import com.firstrow.event.EventDtos.SeatMap;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Public, read-only event endpoints (no login needed).
 * The @Min/@Max on parameters are checked by Spring MVC's built-in method validation (400 on violation).
 */
@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events", description = "Browse events and seat maps (public)")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    @Operation(summary = "List upcoming events, soonest first",
            description = "`from` is an ISO-8601 instant, e.g. 2026-12-01T00:00:00Z (default: now).")
    public PageResponse<EventSummary> list(@RequestParam(required = false) EventType type,
                                           @RequestParam(required = false) Instant from,
                                           @RequestParam(defaultValue = "0") @Min(0) int page,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return eventService.listUpcoming(type, from, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one event with its seat counts")
    public EventDetail get(@PathVariable long id) {
        return eventService.getEvent(id);
    }

    @GetMapping("/{id}/seats")
    @Operation(summary = "Seat map with each seat's effective status (expired holds count as AVAILABLE)")
    public SeatMap seats(@PathVariable long id) {
        return eventService.getSeatMap(id);
    }
}
