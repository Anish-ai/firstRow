package com.firstrow.event;

import com.firstrow.venue.VenueView;

import java.time.Instant;
import java.util.List;

/** JSON shapes returned by the events API. Entities are never serialised directly. */
public final class EventDtos {

    private EventDtos() {
    }

    public record EventSummary(Long id, String title, EventType type, Instant startsAt, EventStatus status,
                               VenueView venue) {

        public static EventSummary from(Event event) {
            return new EventSummary(event.getId(), event.getTitle(), event.getType(), event.getStartsAt(),
                    event.getStatus(), VenueView.from(event.getVenue()));
        }
    }

    public record EventDetail(Long id, String title, EventType type, Instant startsAt, EventStatus status,
                              VenueView venue, int totalSeats, int availableSeats) {

        public static EventDetail from(Event event, EventSeatRepository.SeatCounts counts) {
            return new EventDetail(event.getId(), event.getTitle(), event.getType(), event.getStartsAt(),
                    event.getStatus(), VenueView.from(event.getVenue()), counts.totalSeats(),
                    counts.availableSeats());
        }
    }

    public record SeatMap(Long eventId, List<SeatView> seats) {
    }
}
