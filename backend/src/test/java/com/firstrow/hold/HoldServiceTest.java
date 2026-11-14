package com.firstrow.hold;

import com.firstrow.common.ApiException;
import com.firstrow.config.FirstRowProperties;
import com.firstrow.event.Event;
import com.firstrow.event.EventRepository;
import com.firstrow.event.EventType;
import com.firstrow.event.SeatView;
import com.firstrow.hold.HoldDtos.HoldResponse;
import com.firstrow.observability.BookingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.firstrow.venue.Venue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Business rules of holding, with the database mocked out. The real SQL is covered by the integration tests. */
class HoldServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    private static final long USER = 7L;
    private static final long EVENT = 1L;

    private HoldRepository holds;
    private EventRepository events;
    private HoldService service;

    @BeforeEach
    void setUp() {
        holds = mock(HoldRepository.class);
        events = mock(EventRepository.class);
        FirstRowProperties props = new FirstRowProperties(
                new FirstRowProperties.Jwt("x".repeat(32), Duration.ofHours(1), "firstrow"),
                new FirstRowProperties.Cors(List.of()),
                new FirstRowProperties.Admin(null, null),
                new FirstRowProperties.Holds(Duration.ofMinutes(5), 6, 10));
        service = new HoldService(holds, events, props, Clock.fixed(NOW, ZoneOffset.UTC),
                new BookingMetrics(new SimpleMeterRegistry()));

        when(events.findById(EVENT)).thenReturn(Optional.of(event(NOW.plus(Duration.ofDays(3)))));
        when(holds.insertHold(any(), anyLong(), anyLong(), anyInt(), any()))
                .thenReturn(OffsetDateTime.ofInstant(NOW.plus(Duration.ofMinutes(5)), ZoneOffset.UTC));
    }

    @Test
    void holdsSeatsAndTotalsTheirPrices() {
        when(holds.holdSeats(any(), eq(EVENT), eq(List.of(10L, 11L)), any()))
                .thenReturn(List.of(seat(10L, 45_000), seat(11L, 75_000)));

        HoldResponse response = service.createHold(USER, EVENT, List.of(10L, 11L));

        assertThat(response.totalPaise()).isEqualTo(120_000);
        assertThat(response.seats()).extracting(SeatView::id).containsExactly(10L, 11L);
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        verify(holds).insertHold(eq(response.holdId()), eq(USER), eq(EVENT), eq(2), eq(Duration.ofMinutes(5)));
    }

    @Test
    void moreThanSixSeatsIsRejectedBeforeTouchingTheDatabase() {
        assertBadRequest(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), "You can hold at most 6 seats at a time");
        verifyNoInteractions(holds);
    }

    @Test
    void exactlySixSeatsIsAllowed() {
        List<Long> six = List.of(1L, 2L, 3L, 4L, 5L, 6L);
        when(holds.holdSeats(any(), eq(EVENT), eq(six), any()))
                .thenReturn(six.stream().map(id -> seat(id, 100)).toList());

        assertThat(service.createHold(USER, EVENT, six).seats()).hasSize(6);
    }

    @Test
    void duplicateOrEmptySeatListsAreRejected() {
        assertBadRequest(List.of(1L, 1L), "The same seat was requested more than once");
        assertBadRequest(List.of(), "Choose at least one seat");
        verifyNoInteractions(holds);
    }

    @Test
    void partialClaimFailsWithTheTakenSeats() {
        when(holds.holdSeats(any(), eq(EVENT), eq(List.of(12L, 10L, 11L)), any()))
                .thenReturn(List.of(seat(10L, 100)));
        when(holds.findExistingSeatIds(EVENT, List.of(11L, 12L))).thenReturn(Set.of(11L, 12L));

        assertThatThrownBy(() -> service.createHold(USER, EVENT, List.of(12L, 10L, 11L)))
                .isInstanceOfSatisfying(SeatsUnavailableException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getUnavailableSeatIds()).containsExactly(11L, 12L);
                });
    }

    @Test
    void seatFromAnotherEventIsBadRequestNotConflict() {
        when(holds.holdSeats(any(), eq(EVENT), eq(List.of(10L, 999L)), any())).thenReturn(List.of(seat(10L, 100)));
        when(holds.findExistingSeatIds(EVENT, List.of(999L))).thenReturn(Set.of());

        assertBadRequest(List.of(10L, 999L), "Seats [999] do not belong to event 1");
    }

    @Test
    void unknownEventIsNotFound() {
        when(events.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createHold(USER, 42L, List.of(1L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void eventThatAlreadyStartedCannotBeHeld() {
        when(events.findById(EVENT)).thenReturn(Optional.of(event(NOW.minusSeconds(60))));

        assertThatThrownBy(() -> service.createHold(USER, EVENT, List.of(1L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(holds);
    }

    @Test
    void releasingAnotherUsersOrUnknownHoldIsNotFound() {
        UUID holdId = UUID.randomUUID();
        when(holds.markReleased(holdId, USER)).thenReturn(0);
        when(holds.findStatus(holdId, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.releaseHold(USER, holdId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void releasingAnAlreadyFinishedHoldIsConflict() {
        UUID holdId = UUID.randomUUID();
        when(holds.markReleased(holdId, USER)).thenReturn(0);
        when(holds.findStatus(holdId, USER)).thenReturn(Optional.of("EXPIRED"));

        assertThatThrownBy(() -> service.releaseHold(USER, holdId))
                .isInstanceOf(ApiException.class)
                .hasMessage("Hold is already expired");
    }

    @Test
    void releasingAnActiveHoldFreesItsSeats() {
        UUID holdId = UUID.randomUUID();
        when(holds.markReleased(holdId, USER)).thenReturn(1);

        service.releaseHold(USER, holdId);

        verify(holds).freeSeats(holdId);
    }

    private void assertBadRequest(List<Long> seatIds, String message) {
        assertThatThrownBy(() -> service.createHold(USER, EVENT, seatIds))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage(message);
    }

    private static Event event(Instant startsAt) {
        Event event = new Event(new Venue("Test Hall", "Pune"), "Test Event", EventType.MATCH, startsAt);
        ReflectionTestUtils.setField(event, "id", EVENT);
        return event;
    }

    private static SeatView seat(long id, long pricePaise) {
        return new SeatView(id, "A", "A", (int) id, "GOLD", pricePaise, "HELD");
    }
}
