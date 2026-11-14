package com.firstrow.hold;

import com.firstrow.observability.BookingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Housekeeping, NOT correctness. Expired holds are already treated as AVAILABLE everywhere by lazy expiry,
 * which matters because on Render's free tier the server sleeps and this job simply doesn't run.
 * The job just tidies up the stored data so it matches reality.
 *
 * Safe if several app instances run it at once: both statements are idempotent conditional UPDATEs.
 */
@Component
public class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

    private final HoldRepository holds;
    private final BookingMetrics metrics;

    public HoldExpiryJob(HoldRepository holds, BookingMetrics metrics) {
        this.holds = holds;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${firstrow.holds.cleanup-interval}",
            initialDelayString = "${firstrow.holds.cleanup-interval}")
    @Transactional
    public CleanupResult releaseExpiredHolds() {
        int seatsFreed = holds.freeExpiredSeats();
        int holdsExpired = holds.markExpiredHolds();
        metrics.holdsExpired(holdsExpired);
        if (seatsFreed > 0 || holdsExpired > 0) {
            log.info("holds.expired holds={} seatsFreed={}", holdsExpired, seatsFreed);
        }
        return new CleanupResult(holdsExpired, seatsFreed);
    }

    public record CleanupResult(int holdsExpired, int seatsFreed) {
    }
}
