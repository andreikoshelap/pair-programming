package com.gatto.wise.idempotent;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class IdempotentProcessor<R> {

    private static final int MAX_RETRY = 5;
    private final Clock clock;
    private final Duration keyTtl;
    private final long waitTimeoutNanos;
    private final ConcurrentMap<String, Entry<R>> entries = new ConcurrentHashMap<>();

    public IdempotentProcessor(Clock clock, Duration keyTtl, Duration waitTimeout) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.keyTtl = requirePositive(keyTtl, "keyTtl");
        this.waitTimeoutNanos = TimeUnit.NANOSECONDS.convert(requirePositive(waitTimeout, "waitTimeout"));
    }

    /**
     * Shares an in-flight action per key and caches its successful result for keyTtl
     * after completion. Duplicate callers retry failed attempts up to MAX_RETRY times.
     * waitTimeout bounds each wait for another caller's result, not action execution.
     */
    public R process(String idempotencyKey, Supplier<R> action) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(action, "action");
        int retries = 0;
        while (true) {
            Entry<R> mine = new Entry<>();
            Entry<R> existing = entries.putIfAbsent(idempotencyKey, mine);

            if (existing == null) {
                // We inserted the entry, so we own the execution
                try {
                    R result = action.get();
                    mine.expiresAt = clock.instant().plus(keyTtl);
                    mine.future.complete(result);
                    return result;
                } catch (Throwable t) {
                    entries.remove(idempotencyKey, mine);   // failed attempt must not block retries
                    mine.future.completeExceptionally(t);
                    throw t;
                }
            }
            if (existing.future.isDone()
                    && existing.expiresAt != null
                    && !clock.instant().isBefore(existing.expiresAt)) {
                entries.remove(idempotencyKey, existing);
                continue;
            }
            try {
                return existing.future.get(waitTimeoutNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                throw new IllegalStateException("Timed out waiting for the result", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Waiting interrupted", e);
            } catch (ExecutionException e) {
                if (retries++ >= MAX_RETRY || e.getCause() instanceof Error) {
                    throw new CompletionException(e.getCause());
                }
            }
        }

    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
