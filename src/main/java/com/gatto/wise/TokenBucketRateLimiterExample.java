package com.gatto.wise;

import com.gatto.wise.rate.RateLimiter;
import com.gatto.wise.rate.TokenBucketRateLimiter;

import java.time.Duration;
import java.time.Instant;

public class TokenBucketRateLimiterExample {

    public static void main(String[] args) {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-29T12:00:00Z"));
        RateLimiter limiter = new TokenBucketRateLimiter(3, 1, clock);

        System.out.println("Capacity: 3 tokens per client; refill: 1 token per second.");
        System.out.println("Initial burst: alice can make only 3 requests.");
        request(limiter, "alice", 4);

        System.out.println("Bob has an independent bucket.");
        request(limiter, "bob", 1);

        clock.advance(Duration.ofMillis(500));
        System.out.println("After 0.5 seconds: alice has only half a token.");
        request(limiter, "alice", 1);

        clock.advance(Duration.ofMillis(500));
        System.out.println("After another 0.5 seconds: alice can make 1 request.");
        request(limiter, "alice", 2);

        clock.advance(Duration.ofSeconds(10));
        System.out.println("After 10 seconds: refill is capped at 3 tokens.");
        request(limiter, "alice", 4);
    }

    private static void request(RateLimiter limiter, String clientId, int count) {
        for (int i = 1; i <= count; i++) {
            boolean allowed = limiter.tryAcquire(clientId);
            System.out.printf("  %s request #%d: %s%n", clientId, i,
                    allowed ? "ALLOWED" : "REJECTED");
        }
    }
}
