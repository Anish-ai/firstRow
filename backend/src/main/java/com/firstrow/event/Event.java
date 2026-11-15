package com.firstrow.event;

import com.firstrow.venue.Venue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** LAZY: only loaded when needed; queries that need it fetch it explicitly (see EventRepository). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType type;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status;

    /**
     * Optimistic locking: Hibernate adds "AND version = ?" to every UPDATE and bumps the number. If another
     * transaction changed the row first, nothing matches and Hibernate throws instead of overwriting it.
     */
    @Version
    @Column(nullable = false)
    private long version;

    protected Event() {
        // for JPA
    }

    public Event(Venue venue, String title, EventType type, Instant startsAt) {
        this.venue = venue;
        this.title = title;
        this.type = type;
        this.startsAt = startsAt;
        this.status = EventStatus.SCHEDULED;
    }

    public Long getId() {
        return id;
    }

    public Venue getVenue() {
        return venue;
    }

    public String getTitle() {
        return title;
    }

    public EventType getType() {
        return type;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public EventStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    /** Admin edit of the details people see. Seats and prices are not editable (they may already be sold). */
    public void update(Venue venue, String title, EventType type, Instant startsAt) {
        this.venue = venue;
        this.title = title;
        this.type = type;
        this.startsAt = startsAt;
    }

    public void cancel() {
        this.status = EventStatus.CANCELLED;
    }
}
