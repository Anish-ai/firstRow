package com.firstrow.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Token bucket, one bucket per key (user id).
 * Each bucket holds up to {@code capacity} tokens and refills continuously at capacity-per-minute.
 * A request takes one token; with none left it is rejected. This allows short bursts
 * (up to capacity at once) while capping the long-run rate.
 *
 * In-memory: limits are per app instance and reset on restart. With several instances
 * you'd keep the buckets in Redis so all instances share them.
 */
public class TokenBucketRateLimiter {

    private static final long NANOS_PER_MINUTE = 60_000_000_000L;

    private final int capacity;
    private final double tokensPerNano;
    private final LongSupplier nanoClock;
    private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(int requestsPerMinute, LongSupplier nanoClock) {
        this.capacity = requestsPerMinute;
        this.tokensPerNano = (double) requestsPerMinute / NANOS_PER_MINUTE;
        this.nanoClock = nanoClock;
    }

    /** Takes a token for this key, or throws a 429 saying how many seconds to wait. */
    public void acquire(long key) {
        long now = nanoClock.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        long waitNanos = bucket.tryConsume(now);
        if (waitNanos > 0) {
            throw new RateLimitExceededException(Math.max(1, (long) Math.ceil(waitNanos / 1e9)));
        }
    }

    /** Drops buckets that have refilled completely (they'd behave exactly like a new bucket), so memory stays bounded. */
    public void evictFullBuckets() {
        long now = nanoClock.getAsLong();
        buckets.values().removeIf(bucket -> bucket.isFull(now));
    }

    int bucketCount() {
        return buckets.size();
    }

    private final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefillNanos = now;
        }

        /** Returns 0 if a token was taken, otherwise the nanoseconds until one becomes available. */
        synchronized long tryConsume(long now) {
            refill(now);
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return (long) Math.ceil((1 - tokens) / tokensPerNano);
        }

        synchronized boolean isFull(long now) {
            refill(now);
            return tokens >= capacity;
        }

        private void refill(long now) {
            tokens = Math.min(capacity, tokens + (now - lastRefillNanos) * tokensPerNano);
            lastRefillNanos = now;
        }
    }
}
