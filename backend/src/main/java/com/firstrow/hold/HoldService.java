package com.firstrow.hold;

import com.firstrow.common.ApiException;
import com.firstrow.config.FirstRowProperties;
import com.firstrow.event.Event;
import com.firstrow.event.EventRepository;
import com.firstrow.event.EventStatus;
import com.firstrow.event.SeatView;
import com.firstrow.hold.HoldDtos.HoldResponse;
import com.firstrow.hold.HoldRepository.ActiveHold;
import com.firstrow.observability.BookingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Holding seats. Runs at Postgres's default isolation level, READ COMMITTED: the conditional
 * UPDATE in HoldRepository is atomic on its own, so we don't need a stricter (slower) level.
 *
 * Rules:
 * - A user has at most ONE active hold per event (enforced by a unique index, see V5). To change seats,
 *   they edit that hold instead of creating another one.
 * - A user can have at most {@code maxSeats} (6) seats per event, counting seats already booked.
 * - Editing a hold never extends its expiry, otherwise seats could be kept forever by editing every few minutes.
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

        // An old hold whose time is up must not count as "already holding" (lazy expiry).
        holds.expireStaleHold(userId, eventId);

        UUID holdId = UUID.randomUUID();
        OffsetDateTime expiresAt;
        try {
            // If another request from this user for this event is in flight, this INSERT waits for it
            // (unique index) and fails if that one committed. From here on, no other hold request of
            // this user for this event can run at the same time, so the count below can't go stale.
            expiresAt = holds.insertHold(holdId, userId, eventId, seatIds.size(), props.ttl());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict(
                    "You already have seats on hold for this event. Change or release that hold instead.");
        }
        checkPerEventLimit(userId, eventId, seatIds.size());

        List<SeatView> held = holds.holdSeats(holdId, eventId, seatIds, expiresAt);
        if (held.size() != seatIds.size()) {
            failWithUnavailableSeats(userId, eventId, seatIds, held);
        }

        long totalPaise = total(held);
        metrics.holdCreated();
        log.info("hold.created holdId={} userId={} eventId={} seats={} totalPaise={} expiresAt={}",
                holdId, userId, eventId, seatIds.size(), totalPaise, expiresAt);
        return new HoldResponse(holdId, eventId, expiresAt.toInstant(), held, totalPaise);
    }

    /**
     * Replaces the seats of an active hold with {@code seatIds}: seats no longer wanted are freed, new ones are
     * claimed with the same atomic UPDATE as a new hold. All-or-nothing: if any new seat is taken, the
     * transaction rolls back and the hold keeps exactly the seats it had. The expiry time does not change.
     */
    @Transactional
    public HoldResponse updateHoldSeats(long userId, UUID holdId, List<Long> seatIds) {
        validateSeatIds(seatIds);
        ActiveHold hold = holds.lockActiveHold(holdId, userId)
                .orElseThrow(() -> holdNotActive(holdId, userId));
        checkPerEventLimit(userId, hold.eventId(), seatIds.size());

        Set<Long> current = new HashSet<>();
        holds.findHeldSeats(holdId).forEach(seat -> current.add(seat.id()));
        List<Long> toRemove = current.stream().filter(id -> !seatIds.contains(id)).sorted().toList();
        List<Long> toAdd = seatIds.stream().filter(id -> !current.contains(id)).toList();

        if (!toRemove.isEmpty()) {
            holds.freeSeats(holdId, toRemove);
        }
        if (!toAdd.isEmpty()) {
            List<SeatView> claimed = holds.holdSeats(holdId, hold.eventId(), toAdd, hold.expiresAt());
            if (claimed.size() != toAdd.size()) {
                failWithUnavailableSeats(userId, hold.eventId(), toAdd, claimed);
            }
        }
        holds.updateSeatCount(holdId, seatIds.size());

        List<SeatView> seats = holds.findHeldSeats(holdId);
        log.info("hold.updated holdId={} userId={} eventId={} added={} removed={} seats={}",
                holdId, userId, hold.eventId(), toAdd.size(), toRemove.size(), seats.size());
        return new HoldResponse(holdId, hold.eventId(), hold.expiresAt().toInstant(), seats, total(seats));
    }

    /** The user's active hold for this event, if they have one. */
    @Transactional(readOnly = true)
    public Optional<HoldResponse> findMyHold(long userId, long eventId) {
        return holds.findActiveHoldForEvent(userId, eventId).map(this::toResponse);
    }

    /** One of the user's holds, only while it is active. Someone else's hold is "not found". */
    @Transactional(readOnly = true)
    public HoldResponse getActiveHold(long userId, UUID holdId) {
        return holds.findActiveHold(holdId, userId).map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound(
                        "This hold is no longer active. It may have expired, been released or already been paid for."));
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

    /** Booked + held seats per user per event must stay within maxSeats. */
    private void checkPerEventLimit(long userId, long eventId, int wanted) {
        int booked = holds.countBookedSeats(userId, eventId);
        if (booked + wanted > props.maxSeats()) {
            int left = Math.max(0, props.maxSeats() - booked);
            throw ApiException.conflict(left == 0
                    ? "You already have " + booked + " seats booked for this event, the maximum per person."
                    : "You can have at most " + props.maxSeats() + " seats per event. You've already booked "
                      + booked + ", so you can choose " + left + " more.");
        }
    }

    /** Tells "seat taken" (409, with the seat ids) apart from "not a seat of this event" (400). */
    private void failWithUnavailableSeats(long userId, long eventId, List<Long> requested, List<SeatView> claimed) {
        List<Long> notHeld = missingSeatIds(requested, claimed);
        Set<Long> existing = holds.findExistingSeatIds(eventId, notHeld);
        List<Long> notInEvent = notHeld.stream().filter(id -> !existing.contains(id)).toList();
        if (!notInEvent.isEmpty()) {
            throw ApiException.badRequest("Seats " + notInEvent + " do not belong to event " + eventId);
        }
        metrics.holdConflict();
        log.info("hold.conflict userId={} eventId={} requested={} unavailable={}",
                userId, eventId, requested.size(), notHeld);
        throw new SeatsUnavailableException(notHeld);
    }

    private ApiException holdNotActive(UUID holdId, long userId) {
        var state = holds.findState(holdId, userId)
                .orElseThrow(() -> ApiException.notFound("Hold " + holdId + " not found"));
        return switch (state.status()) {
            case "CONVERTED" -> ApiException.conflict("This hold has already been booked");
            case "RELEASED" -> ApiException.conflict("This hold was released. Please choose your seats again.");
            default -> ApiException.conflict("Your hold has expired. Please choose your seats again.");
        };
    }

    private HoldResponse toResponse(ActiveHold hold) {
        List<SeatView> seats = holds.findHeldSeats(hold.id());
        return new HoldResponse(hold.id(), hold.eventId(), hold.expiresAt().toInstant(), seats, total(seats));
    }

    private static long total(List<SeatView> seats) {
        return seats.stream().mapToLong(SeatView::pricePaise).sum();
    }

    private static List<Long> missingSeatIds(List<Long> requested, List<SeatView> held) {
        Set<Long> heldIds = new HashSet<>();
        held.forEach(seat -> heldIds.add(seat.id()));
        return requested.stream().filter(id -> !heldIds.contains(id)).sorted().toList();
    }
}
