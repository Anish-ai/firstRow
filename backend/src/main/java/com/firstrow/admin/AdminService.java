package com.firstrow.admin;

import com.firstrow.admin.AdminDtos.CreateEventRequest;
import com.firstrow.admin.AdminDtos.CreateVenueRequest;
import com.firstrow.admin.AdminDtos.UpdateEventRequest;
import com.firstrow.common.ApiException;
import com.firstrow.event.Event;
import com.firstrow.event.EventDtos.EventDetail;
import com.firstrow.event.EventRepository;
import com.firstrow.event.EventSeatRepository;
import com.firstrow.event.EventStatus;
import com.firstrow.event.SeatSpec;
import com.firstrow.venue.Venue;
import com.firstrow.venue.VenueRepository;
import com.firstrow.venue.VenueView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final VenueRepository venues;
    private final EventRepository events;
    private final EventSeatRepository seats;
    private final EventCancellationRepository cancellations;

    public AdminService(VenueRepository venues, EventRepository events, EventSeatRepository seats,
                        EventCancellationRepository cancellations) {
        this.venues = venues;
        this.events = events;
        this.seats = seats;
        this.cancellations = cancellations;
    }

    @Transactional
    public VenueView createVenue(CreateVenueRequest request) {
        Venue venue = venues.save(new Venue(request.name().trim(), request.city().trim()));
        log.info("venue.created venueId={} name=\"{}\"", venue.getId(), venue.getName());
        return VenueView.from(venue);
    }

    /**
     * Creates the event and all of its seats in ONE transaction:
     * either the event and every seat exist afterwards, or (on any error) nothing does.
     */
    @Transactional
    public EventDetail createEvent(CreateEventRequest request) {
        // Validate the layout before touching the database.
        List<SeatSpec> seatSpecs = SeatLayoutGenerator.generate(request.layout());

        Venue venue = venues.findById(request.venueId())
                .orElseThrow(() -> ApiException.badRequest("Venue " + request.venueId() + " does not exist"));

        Event event = events.save(new Event(venue, request.title().trim(), request.type(), request.startsAt()));
        seats.insertAll(event.getId(), seatSpecs);

        log.info("event.created eventId={} type={} seats={} title=\"{}\"",
                event.getId(), event.getType(), seatSpecs.size(), event.getTitle());
        return EventDetail.from(event, new EventSeatRepository.SeatCounts(seatSpecs.size(), seatSpecs.size()));
    }

    /**
     * Edits title, type, start time and venue. Optimistic locking: the request carries the version the
     * admin's form was loaded with. If it doesn't match, or another edit commits between our read and our
     * write (Hibernate's "UPDATE ... WHERE version = ?" then matches nothing), the result is a 409.
     */
    @Transactional
    public EventDetail updateEvent(long eventId, UpdateEventRequest request) {
        Event event = events.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("Event " + eventId + " not found"));
        if (event.getStatus() == EventStatus.CANCELLED) {
            throw ApiException.conflict("This event has been cancelled and can no longer be edited");
        }
        if (event.getVersion() != request.version()) {
            throw staleEdit();
        }
        Venue venue = venues.findById(request.venueId())
                .orElseThrow(() -> ApiException.badRequest("Venue " + request.venueId() + " does not exist"));

        event.update(venue, request.title().trim(), request.type(), request.startsAt());
        events.saveAndFlush(event); // flush now, so a version clash surfaces here as a 409

        log.info("event.updated eventId={} version={} title=\"{}\" startsAt={}",
                eventId, event.getVersion(), event.getTitle(), event.getStartsAt());
        return EventDetail.from(event, seats.countSeats(eventId));
    }

    /**
     * Cancels the event in one transaction: no new holds (holds check the status), active holds released,
     * confirmed bookings cancelled, seats freed. Cancelling twice is harmless.
     * Holds are released BEFORE bookings are cancelled: that way a confirm that is running at this very
     * moment either finishes first (and its booking is then cancelled below) or finds its hold released.
     */
    @Transactional
    public EventDetail cancelEvent(long eventId) {
        Event event = events.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("Event " + eventId + " not found"));
        if (event.getStatus() != EventStatus.CANCELLED) {
            event.cancel();
            events.saveAndFlush(event);
            int holdsReleased = cancellations.releaseActiveHolds(eventId);
            int bookedSeatsCancelled = cancellations.cancelBookings(eventId);
            cancellations.freeAllSeats(eventId);
            log.info("event.cancelled eventId={} holdsReleased={} bookedSeatsCancelled={}",
                    eventId, holdsReleased, bookedSeatsCancelled);
        }
        return EventDetail.from(event, seats.countSeats(eventId));
    }

    static ApiException staleEdit() {
        return ApiException.conflict("This event was changed by someone else. Reload it and try again.");
    }
}
