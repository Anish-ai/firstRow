package com.firstrow.admin;

import com.firstrow.admin.AdminDtos.CreateEventRequest;
import com.firstrow.admin.AdminDtos.CreateVenueRequest;
import com.firstrow.admin.AdminDtos.UpdateEventRequest;
import com.firstrow.event.EventDtos.EventDetail;
import com.firstrow.venue.VenueView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** ADMIN-only endpoints. The role check itself lives in SecurityConfig (/api/v1/admin/** needs ROLE_ADMIN). */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin", description = "Create venues; create, edit and cancel events (ADMIN role)")
@SecurityRequirement(name = "bearerAuth")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping("/venues")
    @Operation(summary = "Create a venue")
    public ResponseEntity<VenueView> createVenue(@Valid @RequestBody CreateVenueRequest request) {
        VenueView venue = adminService.createVenue(request);
        return ResponseEntity.status(201).body(venue);
    }

    @PostMapping("/events")
    @Operation(summary = "Create an event and generate its seats from a layout spec")
    public ResponseEntity<EventDetail> createEvent(@Valid @RequestBody CreateEventRequest request) {
        EventDetail event = adminService.createEvent(request);
        return ResponseEntity.created(URI.create("/api/v1/events/" + event.id())).body(event);
    }

    @PutMapping("/events/{id}")
    @Operation(summary = "Edit an event's title, type, start time and venue",
            description = "Send the `version` from the event you loaded. 409 if the event changed since "
                    + "(optimistic locking) or was cancelled. Seat layout and prices can't be edited.")
    public EventDetail updateEvent(@PathVariable long id, @Valid @RequestBody UpdateEventRequest request) {
        return adminService.updateEvent(id, request);
    }

    @PostMapping("/events/{id}/cancel")
    @Operation(summary = "Cancel an event",
            description = "Stops new holds, releases active holds and cancels confirmed bookings. Idempotent.")
    public EventDetail cancelEvent(@PathVariable long id) {
        return adminService.cancelEvent(id);
    }
}
