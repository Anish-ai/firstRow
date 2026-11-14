package com.firstrow.event;

import com.firstrow.common.ApiException;
import com.firstrow.common.PageResponse;
import com.firstrow.event.EventDtos.EventDetail;
import com.firstrow.event.EventDtos.EventSummary;
import com.firstrow.event.EventDtos.SeatMap;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@Transactional(readOnly = true)
public class EventService {

    private final EventRepository events;
    private final EventSeatRepository seats;
    private final Clock clock;

    public EventService(EventRepository events, EventSeatRepository seats, Clock clock) {
        this.events = events;
        this.seats = seats;
        this.clock = clock;
    }

    /** Scheduled events starting at or after {@code from} (default: now), soonest first. */
    public PageResponse<EventSummary> listUpcoming(EventType type, Instant from, int page, int size) {
        Instant start = (from != null) ? from : clock.instant();
        PageRequest pageable = PageRequest.of(page, size, Sort.by("startsAt", "id"));
        Page<Event> result = (type == null)
                ? events.findByStatusAndStartsAtGreaterThanEqual(EventStatus.SCHEDULED, start, pageable)
                : events.findByStatusAndTypeAndStartsAtGreaterThanEqual(EventStatus.SCHEDULED, type, start, pageable);
        return PageResponse.from(result, EventSummary::from);
    }

    public EventDetail getEvent(long eventId) {
        Event event = findEvent(eventId);
        return EventDetail.from(event, seats.countSeats(eventId));
    }

    public SeatMap getSeatMap(long eventId) {
        findEvent(eventId); // 404 if the event doesn't exist
        return new SeatMap(eventId, seats.findSeatMap(eventId));
    }

    private Event findEvent(long eventId) {
        return events.findWithVenueById(eventId)
                .orElseThrow(() -> ApiException.notFound("Event " + eventId + " not found"));
    }
}
