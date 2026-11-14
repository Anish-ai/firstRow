package com.firstrow.hold;

import com.firstrow.common.ApiException;
import com.firstrow.config.FirstRowProperties;
import com.firstrow.event.Event;
import com.firstrow.event.EventRepository;
import com.firstrow.event.EventStatus;
import com.firstrow.event.SeatView;
import com.firstrow.hold.HoldDtos.HoldResponse;
import com.firstrow.observability.BookingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Holding seats. Runs at Postgres's default isolation level, READ COMMITTED: the conditional
 * UPDATE in HoldRepository is atomic on its own, so we don't need a stricter (slower) level.
 */
@Service
public class HoldService {

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    private final HoldRepository holds;
    private final EventRepository events;
    private final FirstRowProperties.Holds props;
    private final Clock clock;
    private final BookingMetrics metrics;

    public HoldService(HoldRepository holds, EventRepository events, FirstRowProperties properties, Clock clock,
                       BookingMetrics metrics) {
        this.holds = holds;
        this.events = events;
        this.props = properties.holds();
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * All-or-nothing: either every requested seat is held, or none is.
     * Throwing from this @Transactional method rolls back the hold row AND any seats the UPDATE already claimed.
     */
    @Transactional
    public HoldResponse createHold(long userId, long eventId, List<Long> seatIds) {
        validateSeatIds(seatIds);
        Event event = events.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("Event " + eventId + " not found"));
        if (event.getStatus() != EventStatus.SCHEDULED || !event.getStartsAt().isAfter(clock.instant())) {
            throw ApiException.conflict("This event is not open for booking");
        }

        UUID holdId = UUID.randomUUID();
        OffsetDateTime expiresAt = holds.insertHold(holdId, userId, eventId, seatIds.size(), props.ttl());
        List<SeatView> held = holds.holdSeats(holdId, eventId, seatIds, expiresAt);

        if (held.size() != seatIds.size()) {
            List<Long> notHeld = missingSeatIds(seatIds, held);
            Set<Long> existing = holds.findExistingSeatIds(eventId, notHeld);
            List<Long> notInEvent = notHeld.stream().filter(id -> !existing.contains(id)).toList();
            if (!notInEvent.isEmpty()) {
                throw ApiException.badRequest("Seats " + notInEvent + " do not belong to event " + eventId);
            }
            metrics.holdConflict();
            log.info("hold.conflict userId={} eventId={} requested={} unavailable={}",
                    userId, eventId, seatIds.size(), notHeld);
            throw new SeatsUnavailableException(notHeld);
        }

        long totalPaise = held.stream().mapToLong(SeatView::pricePaise).sum();
        metrics.holdCreated();
        log.info("hold.created holdId={} userId={} eventId={} seats={} totalPaise={} expiresAt={}",
                holdId, userId, eventId, seatIds.size(), totalPaise, expiresAt);
        return new HoldResponse(holdId, eventId, expiresAt.toInstant(), held, totalPaise);
    }

    /** Releases an ACTIVE hold early. Someone else's hold is reported as "not found" (we don't reveal it exists). */
    @Transactional
    public void releaseHold(long userId, UUID holdId) {
        if (holds.markReleased(holdId, userId) == 0) {
            String status = holds.findStatus(holdId, userId)
                    .orElseThrow(() -> ApiException.notFound("Hold " + holdId + " not found"));
            throw ApiException.conflict("Hold is already " + status.toLowerCase());
        }
        int seatsFreed = holds.freeSeats(holdId);
        log.info("hold.released holdId={} userId={} seatsFreed={}", holdId, userId, seatsFreed);
    }

    void validateSeatIds(List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            throw ApiException.badRequest("Choose at least one seat");
        }
        if (seatIds.size() > props.maxSeats()) {
            throw ApiException.badRequest("You can hold at most " + props.maxSeats() + " seats at a time");
        }
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw ApiException.badRequest("The same seat was requested more than once");
        }
    }

    private static List<Long> missingSeatIds(List<Long> requested, List<SeatView> held) {
        Set<Long> heldIds = new HashSet<>();
        held.forEach(seat -> heldIds.add(seat.id()));
        return requested.stream().filter(id -> !heldIds.contains(id)).sorted().toList();
    }
}
