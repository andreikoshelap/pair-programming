package com.gatto.wise.braker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class CircuitBreakerTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-19T12:00:00Z"));

    @Test
    void startsClosedAndReturnsServiceResponse() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(30), clock);

        String response = breaker.execute(() -> "ok");

        assertThat(response).isEqualTo("ok");
        assertThat(breaker.currentState()).isEqualTo("CLOSED");
    }

    @Test
    void opensAfterConfiguredNumberOfFailures() {
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(30), clock);

        assertThatThrownBy(() -> breaker.execute(this::externalServiceFailure))
                .isInstanceOf(IllegalStateException.class);
        assertThat(breaker.currentState()).isEqualTo("CLOSED");

        assertThatThrownBy(() -> breaker.execute(this::externalServiceFailure))
                .isInstanceOf(IllegalStateException.class);
        assertThat(breaker.currentState()).isEqualTo("OPEN");
    }

    @Test
    void rejectsRequestsWhileOpen() {
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofSeconds(30), clock);

        assertThatThrownBy(() -> breaker.execute(this::externalServiceFailure))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> breaker.execute(() -> "not called"))
                .isInstanceOf(CircuitBreakerOpenException.class);
    }

    @Test
    void closesAgainAfterSuccessfulTrialRequest() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofSeconds(30), clock);
        assertThatThrownBy(() -> breaker.execute(this::externalServiceFailure))
                .isInstanceOf(IllegalStateException.class);

        clock.advance(Duration.ofSeconds(30));

        String response = breaker.execute(() -> "recovered");

        assertThat(response).isEqualTo("recovered");
        assertThat(breaker.currentState()).isEqualTo("CLOSED");
    }

    @Test
    void reopensWhenTrialRequestFails() {
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofSeconds(30), clock);
        assertThatThrownBy(() -> breaker.execute(this::externalServiceFailure))
                .isInstanceOf(IllegalStateException.class);

        clock.advance(Duration.ofSeconds(30));

        assertThatThrownBy(() -> breaker.execute(this::externalServiceFailure))
                .isInstanceOf(IllegalStateException.class);
        assertThat(breaker.currentState()).isEqualTo("OPEN");
    }

    private String externalServiceFailure() {
        throw new IllegalStateException("external service is down");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
