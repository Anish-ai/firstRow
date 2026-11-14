-- V1: full FirstRow schema.
-- Flyway runs each V<n>__*.sql file exactly once, in order, and records it in flyway_schema_history.
-- Never edit a migration that has already run; add a new V<n+1> file instead.
--
-- Money is always stored as integer paise (BIGINT). 1 rupee = 100 paise. Never floats.
-- Timestamps are TIMESTAMPTZ (stored in UTC).

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(10)  NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE venues (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    city VARCHAR(100) NOT NULL
);

CREATE TABLE events (
    id        BIGSERIAL PRIMARY KEY,
    venue_id  BIGINT       NOT NULL REFERENCES venues (id),
    title     VARCHAR(200) NOT NULL,
    type      VARCHAR(10)  NOT NULL CHECK (type IN ('PREMIERE', 'MATCH')),
    starts_at TIMESTAMPTZ  NOT NULL,
    status    VARCHAR(10)  NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'CANCELLED'))
);

-- The events list is filtered by type and sorted/filtered by start time.
CREATE INDEX idx_events_type_starts_at ON events (type, starts_at);
CREATE INDEX idx_events_starts_at ON events (starts_at);

CREATE TABLE holds (
    id         UUID        PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    event_id   BIGINT      NOT NULL REFERENCES events (id),
    expires_at TIMESTAMPTZ NOT NULL,
    status     VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'CONVERTED', 'EXPIRED', 'RELEASED'))
);

-- The cleanup job looks for ACTIVE holds whose expires_at has passed.
CREATE INDEX idx_holds_status_expires_at ON holds (status, expires_at);

-- One row per seat per event. This table is where the concurrency battle happens.
CREATE TABLE event_seats (
    id          BIGSERIAL PRIMARY KEY,
    event_id    BIGINT      NOT NULL REFERENCES events (id),
    section     VARCHAR(10) NOT NULL,
    row_label   VARCHAR(5)  NOT NULL,
    seat_number INT         NOT NULL CHECK (seat_number > 0),
    price_tier  VARCHAR(20) NOT NULL,
    price_paise BIGINT      NOT NULL CHECK (price_paise >= 0),
    status      VARCHAR(10) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
    hold_id     UUID        REFERENCES holds (id),
    held_until  TIMESTAMPTZ,
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_event_seat UNIQUE (event_id, section, row_label, seat_number),
    -- A HELD seat must say who holds it and until when; other states must not.
    CONSTRAINT chk_hold_fields CHECK (
        (status = 'HELD' AND hold_id IS NOT NULL AND held_until IS NOT NULL)
        OR (status = 'AVAILABLE' AND hold_id IS NULL AND held_until IS NULL)
        OR (status = 'BOOKED')
    )
);

-- Seat map: "all seats of event X", and the hold UPDATE filters by event + status.
CREATE INDEX idx_event_seats_event_status ON event_seats (event_id, status);
-- Releasing / converting a hold finds its seats by hold_id.
CREATE INDEX idx_event_seats_hold_id ON event_seats (hold_id);

CREATE TABLE bookings (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    event_id        BIGINT       NOT NULL REFERENCES events (id),
    hold_id         UUID         NOT NULL UNIQUE REFERENCES holds (id),
    total_paise     BIGINT       NOT NULL CHECK (total_paise >= 0),
    status          VARCHAR(10)  NOT NULL DEFAULT 'CONFIRMED' CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    idempotency_key VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- A retried "confirm" with the same key from the same user must hit this constraint.
    CONSTRAINT uq_booking_user_idempotency_key UNIQUE (user_id, idempotency_key)
);

CREATE INDEX idx_bookings_user_created ON bookings (user_id, created_at DESC);

-- Which seats belong to which booking.
-- A Postgres partial index cannot look at another table (bookings.status), so we copy
-- "is this booking active?" into this table as `active` and keep it in sync on cancel.
CREATE TABLE booking_seats (
    booking_id    BIGINT  NOT NULL REFERENCES bookings (id),
    event_seat_id BIGINT  NOT NULL REFERENCES event_seats (id),
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (booking_id, event_seat_id)
);

-- Second safety net against double booking: a seat can appear in at most ONE active booking.
-- Cancelled bookings set active = false, so the seat can be booked again later.
CREATE UNIQUE INDEX uq_booking_seats_active_seat ON booking_seats (event_seat_id) WHERE active;
