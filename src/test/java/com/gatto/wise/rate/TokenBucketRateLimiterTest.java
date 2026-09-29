package com.gatto.wise.rate;

import com.gatto.wise.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-28T12:00:00Z"));

    @Test
    void startsFullAndKeepsClientsIndependent() {
        var limiter = new TokenBucketRateLimiter(2, 1, clock);

        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isFalse();
        assertThat(limiter.tryAcquire("bob")).isTrue();
    }

    @Test
    void accumulatesSubMillisecondRefills() {
        var limiter = new TokenBucketRateLimiter(1, 1000, clock);
        assertThat(limiter.tryAcquire("alice")).isTrue();

        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofNanos(500_000));
            assertThat(limiter.tryAcquire("alice")).isFalse();
            clock.advance(Duration.ofNanos(500_000));
            assertThat(limiter.tryAcquire("alice")).isTrue();
        }
    }

    @Test
    void doesNotCreditTheSameIntervalAfterClockRollback() {
        var limiter = new TokenBucketRateLimiter(1, 1, clock);
        assertThat(limiter.tryAcquire("alice")).isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("alice")).isTrue();

        clock.advance(Duration.ofSeconds(-1));
        assertThat(limiter.tryAcquire("alice")).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("alice")).isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("alice")).isTrue();
    }

    @Test
    void capsRefillAtCapacity() {
        var limiter = new TokenBucketRateLimiter(2, 1, clock);
        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isTrue();

        clock.advance(Duration.ofDays(1));

        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isFalse();
    }

    @Test
    void zeroRefillRateOnlyAllowsInitialTokens() {
        var limiter = new TokenBucketRateLimiter(1, 0, clock);
        assertThat(limiter.tryAcquire("alice")).isTrue();

        clock.advance(Duration.ofDays(1));

        assertThat(limiter.tryAcquire("alice")).isFalse();
    }

    @Test
    void concurrentRequestsCannotExceedCapacity() throws Exception {
        var limiter = new TokenBucketRateLimiter(3, 1, clock);
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(8);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<Boolean>>();
            try {
                for (int i = 0; i < 8; i++) {
                    results.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return limiter.tryAcquire("alice");
                    }));
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }

            int accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(3);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsInvalidCapacity(int capacity) {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(capacity, 1, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidRefillRate(double refillRate) {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(1, refillRate, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullClock() {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(1, 1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("clock");
    }

    @Test
    void rejectsNullClientId() {
        var limiter = new TokenBucketRateLimiter(1, 1, clock);

        assertThatThrownBy(() -> limiter.tryAcquire(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("clientId");
    }
}
