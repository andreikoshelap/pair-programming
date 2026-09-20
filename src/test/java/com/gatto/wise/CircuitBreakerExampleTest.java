package com.gatto.wise;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class CircuitBreakerExampleTest {

    @Test
    void demonstratesFailuresRejectionAndRecovery(CapturedOutput output) {
        CircuitBreakerExample.main(new String[0]);

        assertThat(output.getOut().lines().filter(line -> !line.isBlank()).toList())
                .containsExactly(
                        "Service call #1, state: CLOSED",
                        "Service failed: Exchange rate service is unavailable",
                        "State after request: CLOSED",
                        "Service call #2, state: CLOSED",
                        "Service failed: Exchange rate service is unavailable",
                        "State after request: OPEN",
                        "Request rejected: circuit breaker is open",
                        "State after request: OPEN",
                        "Advancing the clock by 2 seconds...",
                        "Service call #3, state: HALF_OPEN",
                        "Rate: 1 EUR = 1.10 USD (demo rate)",
                        "State after request: CLOSED",
                        "Service call #4, state: CLOSED",
                        "Rate: 1 EUR = 1.10 USD (demo rate)",
                        "State after request: CLOSED");
        assertThat(output.getErr()).isEmpty();
    }
}
