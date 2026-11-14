package com.firstrow.common;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A fake nanosecond clock makes time-based behaviour deterministic: no sleeps. */
class TokenBucketRateLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(0);
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, now::get);

    @Test
    void allowsABurstUpToCapacityThenRejects() {
        for (int i = 0; i < 10; i++) {
            limiter.acquire(1L);
        }
        assertThatThrownBy(() -> limiter.acquire(1L))
                .isInstanceOf(RateLimitExceededException.class)
                // 10 per minute = one token every 6 seconds
                .extracting(e -> ((RateLimitExceededException) e).getRetryAfterSeconds())
                .isEqualTo(6L);
    }

    @Test
    void refillsOverTime() {
        for (int i = 0; i < 10; i++) {
            limiter.acquire(1L);
        }
        now.addAndGet(6 * SECOND); // one token refilled

        assertThatCode(() -> limiter.acquire(1L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.acquire(1L)).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void eachUserHasTheirOwnBucket() {
        for (int i = 0; i < 10; i++) {
            limiter.acquire(1L);
        }
        assertThatCode(() -> limiter.acquire(2L)).doesNotThrowAnyException();
    }

    @Test
    void evictsOnlyBucketsThatHaveFullyRefilled() {
        limiter.acquire(1L);          // user 1 uses 1 token: full again after 6s
        now.addAndGet(30 * SECOND);
        for (int i = 0; i < 10; i++) {
            limiter.acquire(2L);      // user 2 empties the bucket: needs 60s to refill
        }

        now.addAndGet(31 * SECOND);   // user 1 long since full; user 2 only ~5 of 10 tokens back
        limiter.evictFullBuckets();

        assertThat(limiter.bucketCount()).isEqualTo(1);
    }
}
