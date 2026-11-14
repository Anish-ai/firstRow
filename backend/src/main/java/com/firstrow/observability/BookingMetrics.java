package com.firstrow.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Business metrics, exposed at /actuator/prometheus. Micrometer is a facade (like SLF4J for metrics):
 * code records to a MeterRegistry and the Prometheus registry formats it for scraping.
 *
 * Prometheus names: "holds.granted" becomes holds_granted_total, the "holds.create" timer becomes
 * holds_create_seconds_count / _sum / _bucket (the buckets let Grafana compute p95 across instances).
 *
 * Why "holds.granted" and not "holds.created": in the OpenMetrics format a "_created" suffix is reserved
 * (it carries a counter's creation time), so the Prometheus client silently exported "holds.created"
 * as "holds_total". ObservabilityIntegrationTest checks the exact exported names so this can't regress.
 */
@Component
public class BookingMetrics {

    private final Counter holdsCreated;
    private final Counter holdsConflicts;
    private final Counter holdsExpired;
    private final Counter bookingsConfirmed;
    private final Counter bookingsCancelled;
    private final Timer holdCreateTimer;

    public BookingMetrics(MeterRegistry registry) {
        holdsCreated = Counter.builder("holds.granted")
                .description("Seat holds created successfully").register(registry);
        holdsConflicts = Counter.builder("holds.conflicts")
                .description("Hold requests rejected with 409 because a seat was taken").register(registry);
        holdsExpired = Counter.builder("holds.expired")
                .description("Holds marked EXPIRED by the cleanup job").register(registry);
        bookingsConfirmed = Counter.builder("bookings.confirmed")
                .description("Bookings confirmed (idempotent replays not counted)").register(registry);
        bookingsCancelled = Counter.builder("bookings.cancelled")
                .description("Bookings cancelled").register(registry);
        holdCreateTimer = Timer.builder("holds.create")
                .description("Time to process a hold request, success or conflict")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void holdCreated() {
        holdsCreated.increment();
    }

    public void holdConflict() {
        holdsConflicts.increment();
    }

    public void holdsExpired(int count) {
        holdsExpired.increment(count);
    }

    public void bookingConfirmed() {
        bookingsConfirmed.increment();
    }

    public void bookingCancelled() {
        bookingsCancelled.increment();
    }

    /** Times a hold request, including the ones that end in an exception (409, 400...). */
    public <T> T timeHoldCreation(Supplier<T> action) {
        return holdCreateTimer.record(action);
    }
}
