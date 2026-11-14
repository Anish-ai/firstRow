package com.firstrow.event;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

/**
 * Spring Data generates the SQL from the method names.
 * {@code @EntityGraph("venue")} fetches each event's venue in the same query (a JOIN), avoiding the
 * "N+1 queries" problem where listing 20 events would fire 20 extra SELECTs for their venues.
 */
public interface EventRepository extends JpaRepository<Event, Long> {

    @EntityGraph(attributePaths = "venue")
    Page<Event> findByStatusAndStartsAtGreaterThanEqual(EventStatus status, Instant from, Pageable pageable);

    @EntityGraph(attributePaths = "venue")
    Page<Event> findByStatusAndTypeAndStartsAtGreaterThanEqual(EventStatus status, EventType type, Instant from,
                                                               Pageable pageable);

    @EntityGraph(attributePaths = "venue")
    Optional<Event> findWithVenueById(Long id);
}
