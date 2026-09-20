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

    public CircuitBreaker(int failureThreshold, Duration openStateDuration) {
        this(failureThreshold, openStateDuration, Clock.systemUTC());
    }

    CircuitBreaker(int failureThreshold, Duration openStateDuration, Clock clock) {
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

    public synchronized <T> T execute(ThrowingSupplier<T> supplier) throws Exception {
        Objects.requireNonNull(supplier);

        return switch (state) {
            case CLOSED -> executeClosed(supplier);
            case OPEN -> executeOpen(supplier);
            case HALF_OPEN -> executeHalfOpen(supplier);
        };
    }

    public synchronized String currentState() {
        return state.name();
    }

    private <T> T executeClosed(ThrowingSupplier<T> supplier) throws Exception {
        try {
            T result = supplier.get();
            moveToClosed();
            return result;
        } catch (Exception exception) {
            consecutiveFailures++;
            if (consecutiveFailures >= failureThreshold) {
                moveToOpen();
            }
            throw exception;
        }
    }

    private <T> T executeOpen(ThrowingSupplier<T> supplier) throws Exception {
        Instant reopenAt = openedAt.plus(openStateDuration);
        if (clock.instant().isBefore(reopenAt)) {
            throw new CircuitBreakerOpenException();
        }

        state = State.HALF_OPEN;
        return executeHalfOpen(supplier);
    }

    private <T> T executeHalfOpen(ThrowingSupplier<T> supplier) throws Exception {
        try {
            T result = supplier.get();
            moveToClosed();
            return result;
        } catch (Exception exception) {
            moveToOpen();
            throw exception;
        }
    }

    private void moveToClosed() {
        consecutiveFailures = 0;
        openedAt = null;
        state = State.CLOSED;
    }

    private void moveToOpen() {
        consecutiveFailures = 0;
        openedAt = clock.instant();
        state = State.OPEN;
    }

    private enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
}
