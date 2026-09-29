package com.gatto.wise.rate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;


public class TokenBucketRateLimiter implements RateLimiter {

    private final int capacity;
    private final double refillTokensPerSecond;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(int capacity, double refillTokensPerSecond, Clock clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        // Zero permits only the initial burst, without replenishment.
        if (!Double.isFinite(refillTokensPerSecond) || refillTokensPerSecond < 0) {
            throw new IllegalArgumentException("refillTokensPerSecond must be finite and non-negative");
        }
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean tryAcquire(String clientId) {
        Objects.requireNonNull(clientId, "clientId");
        Bucket bucket = buckets.computeIfAbsent(clientId, id -> new Bucket(capacity, clock.instant()));
        return bucket.tryConsume(capacity, refillTokensPerSecond, clock);
    }

    private static final class Bucket {
        double tokens;
        Instant lastRefillTime;

        Bucket(double initialTokens, Instant now) {
            this.tokens = initialTokens;
            this.lastRefillTime = now;
        }

        synchronized boolean tryConsume(int capacity, double refillTokensPerSecond, Clock clock) {
            Instant now = clock.instant();
            // Ignore clock rollback so an interval cannot be credited twice.
            if (now.isAfter(lastRefillTime)) {
                Duration elapsed = Duration.between(lastRefillTime, now);
                double elapsedSeconds = elapsed.getSeconds() + elapsed.getNano() / 1_000_000_000.0;
                tokens = Math.min(capacity, tokens + elapsedSeconds * refillTokensPerSecond);
                lastRefillTime = now;
            }
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            } else {
                return false;
            }
        }
    }
}
