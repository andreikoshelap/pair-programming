package com.gatto.wise.braker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public class CircuitBreaker {
    private final int failureThreshold;
    private final Duration openStateDuration;
    private final Clock clock;

    private State state;
    private int consecutiveFailures;
    private Instant openedAt;
    private long generation;

    public CircuitBreaker(int failureThreshold, Duration openStateDuration) {
        this(failureThreshold, openStateDuration, Clock.systemUTC());
    }

    public CircuitBreaker(int failureThreshold, Duration openStateDuration, Clock clock) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be positive");
        }
        if (openStateDuration.isNegative() || openStateDuration.isZero()) {
            throw new IllegalArgumentException("openStateDuration must be positive");
        }

        this.failureThreshold = failureThreshold;
        this.openStateDuration = Objects.requireNonNull(openStateDuration);
        this.clock = Objects.requireNonNull(clock);
        this.state = State.CLOSED;
    }

    public synchronized String currentState() {
        return state.name();
    }

    public <T> T call(ThrowingSupplier<T> supplier) throws Exception {
        Objects.requireNonNull(supplier);

        long callGeneration = beforeCall();
        try {
            T result = supplier.get();
            onSuccess(callGeneration);
            return result;
        } catch (Throwable t) {
            onFailure(callGeneration);
            throw t;
        }
    }

    private synchronized long beforeCall() {
        if (state == State.HALF_OPEN) {
            throw new CircuitBreakerOpenException();
        }
        if (state == State.OPEN) {
            if (clock.instant().isBefore(openedAt.plus(openStateDuration))) {
                throw new CircuitBreakerOpenException();
            }
            state = State.HALF_OPEN;
            generation++;
        }
        return generation;
    }

    private synchronized void onSuccess(long callGeneration) {
        // Ignore results from calls admitted before the last state transition.
        if (callGeneration != generation) {
            return;
        }
        if (state == State.HALF_OPEN) {
            moveToClosed();
        } else if (state == State.CLOSED) {
            consecutiveFailures = 0;
        }
    }

    private synchronized void onFailure(long callGeneration) {
        if (callGeneration != generation) {
            return;
        }
        if (state == State.HALF_OPEN) {
            moveToOpen();
        } else if (state == State.CLOSED && ++consecutiveFailures >= failureThreshold) {
            moveToOpen();
        }
    }

    private void moveToClosed() {
        consecutiveFailures = 0;
        openedAt = null;
        state = State.CLOSED;
        generation++;
    }

    private void moveToOpen() {
        consecutiveFailures = 0;
        openedAt = clock.instant();
        state = State.OPEN;
        generation++;
    }

    private enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
}
