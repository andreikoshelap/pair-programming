package com.gatto.wise.braker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gatto.wise.MutableClock;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CircuitBreakerTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-19T12:00:00Z"));

    @Test
    void startsClosedAndReturnsServiceResponse() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(30), clock);

        String response = breaker.call(() -> "ok");

        assertThat(response).isEqualTo("ok");
        assertThat(breaker.currentState()).isEqualTo("CLOSED");
    }

    @Test
    void opensAfterConfiguredNumberOfFailures() {
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(30), clock);

        assertThatThrownBy(() -> breaker.call(this::externalServiceFailure))
                .isInstanceOf(IllegalStateException.class);
        assertThat(breaker.currentState()).isEqualTo("CLOSED");

        assertThatThrownBy(() -> breaker.call(this::externalServiceFailure))
                .isInstanceOf(IllegalStateException.class);
        assertThat(breaker.currentState()).isEqualTo("OPEN");
    }

    @Test
    void rejectsRequestsWhileOpen() {
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofSeconds(30), clock);

        assertThatThrownBy(() -> breaker.call(this::externalServiceFailure))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> breaker.call(() -> "not called"))
                .isInstanceOf(CircuitBreakerOpenException.class);
    }

    @Test
    void closesAgainAfterSuccessfulTrialRequest() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofSeconds(30), clock);
        assertThatThrownBy(() -> breaker.call(this::externalServiceFailure))
                .isInstanceOf(IllegalStateException.class);

        clock.advance(Duration.ofSeconds(30));

        String response = breaker.call(() -> "recovered");

        assertThat(response).isEqualTo("recovered");
        assertThat(breaker.currentState()).isEqualTo("CLOSED");
    }

    @Test
    void reopensWhenTrialRequestFails() {
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofSeconds(30), clock);
        assertThatThrownBy(() -> breaker.call(this::externalServiceFailure))
                .isInstanceOf(IllegalStateException.class);

        clock.advance(Duration.ofSeconds(30));

        assertThatThrownBy(() -> breaker.call(this::externalServiceFailure))
                .isInstanceOf(IllegalStateException.class);
        assertThat(breaker.currentState()).isEqualTo("OPEN");
    }

    @Test
    void successResetsConsecutiveFailures() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(30), clock);
        assertThatThrownBy(() -> breaker.call(this::externalServiceFailure))
                .isInstanceOf(IllegalStateException.class);

        breaker.call(() -> "ok");

        assertThatThrownBy(() -> breaker.call(this::externalServiceFailure))
                .isInstanceOf(IllegalStateException.class);
        assertThat(breaker.currentState()).isEqualTo("CLOSED");
    }

    @Test
    void allowsConcurrentClosedCalls() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(30), clock);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var first = executor.submit(() -> breaker.call(() -> {
                    started.countDown();
                    release.await();
                    return "first";
                }));
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

                var second = executor.submit(() -> breaker.call(() -> "second"));
                assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("second");

                release.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
            } finally {
                release.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ignoresOldResultsAndAllowsOnlyOneProbe(boolean oldCallFails) throws Exception {
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofSeconds(30), clock);
        CountDownLatch oldStarted = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            try {
                var oldCall = executor.submit(() -> {
                    try {
                        return breaker.call(() -> {
                            oldStarted.countDown();
                            releaseOld.await();
                            return oldCallFails ? externalServiceFailure() : "old";
                        });
                    } catch (IllegalStateException exception) {
                        return "failed";
                    }
                });
                assertThat(oldStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> breaker.call(this::externalServiceFailure))
                        .isInstanceOf(IllegalStateException.class);
                clock.advance(Duration.ofSeconds(30));

                var probe = executor.submit(() -> breaker.call(() -> {
                    probeStarted.countDown();
                    releaseProbe.await();
                    return "recovered";
                }));
                assertThat(probeStarted.await(5, TimeUnit.SECONDS)).isTrue();

                releaseOld.countDown();
                assertThat(oldCall.get(5, TimeUnit.SECONDS))
                        .isEqualTo(oldCallFails ? "failed" : "old");
                assertThat(breaker.currentState()).isEqualTo("HALF_OPEN");

                var rejected = executor.submit(() -> {
                    assertThatThrownBy(() -> breaker.call(() -> {
                        throw new AssertionError("Only one probe may run");
                    })).isInstanceOf(CircuitBreakerOpenException.class);
                });
                rejected.get(5, TimeUnit.SECONDS);

                releaseProbe.countDown();
                assertThat(probe.get(5, TimeUnit.SECONDS)).isEqualTo("recovered");
                assertThat(breaker.currentState()).isEqualTo("CLOSED");
            } finally {
                releaseOld.countDown();
                releaseProbe.countDown();
            }
        }
    }

    @Test
    void errorDoesNotLeaveProbeStuckInHalfOpen() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofSeconds(30), clock);
        assertThatThrownBy(() -> breaker.call(this::externalServiceFailure))
                .isInstanceOf(IllegalStateException.class);
        clock.advance(Duration.ofSeconds(30));
        AssertionError failure = new AssertionError("probe failed");

        assertThatThrownBy(() -> breaker.call(() -> { throw failure; }))
                .isSameAs(failure);
        assertThat(breaker.currentState()).isEqualTo("OPEN");
        assertThatThrownBy(() -> breaker.call(() -> "not called"))
                .isInstanceOf(CircuitBreakerOpenException.class);

        clock.advance(Duration.ofSeconds(30));
        assertThat(breaker.call(() -> "recovered")).isEqualTo("recovered");
        assertThat(breaker.currentState()).isEqualTo("CLOSED");
    }

    @Test
    void countsErrorAsFailureAndRethrowsIt() {
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofSeconds(30), clock);
        AssertionError failure = new AssertionError("service failed");

        assertThatThrownBy(() -> breaker.call(() -> { throw failure; }))
                .isSameAs(failure);
        assertThat(breaker.currentState()).isEqualTo("OPEN");
    }

    private String externalServiceFailure() {
        throw new IllegalStateException("external service is down");
    }
}
