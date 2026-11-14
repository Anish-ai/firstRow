package com.firstrow.admin;

import com.firstrow.admin.AdminDtos.CreateEventRequest;
import com.firstrow.admin.AdminDtos.CreateVenueRequest;
import com.firstrow.common.ApiException;
import com.firstrow.event.Event;
import com.firstrow.event.EventDtos.EventDetail;
import com.firstrow.event.EventRepository;
import com.firstrow.event.EventSeatRepository;
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

    public AdminService(VenueRepository venues, EventRepository events, EventSeatRepository seats) {
        this.venues = venues;
        this.events = events;
        this.seats = seats;
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
}
