package com.gatto.wise;

import com.gatto.wise.braker.CircuitBreaker;
import com.gatto.wise.braker.CircuitBreakerOpenException;

import java.io.IOException;
import java.time.Duration;

public class CircuitBreakerExample {
    // Reuse one breaker across calls to the same external service.
    private final CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(2));
    private int serviceCalls;

    public static void main(String[] args) throws InterruptedException {
        CircuitBreakerExample example = new CircuitBreakerExample();

        example.requestRate(); // First failure: CLOSED.
        example.requestRate(); // Second failure: OPEN.
        example.requestRate(); // Rejected without calling the service.

        System.out.println("Waiting for the retry timeout...");
        Thread.sleep(2100); // Only to demonstrate the timeout with real clocks.

        example.requestRate(); // Successful HALF_OPEN probe: CLOSED.
        example.requestRate(); // Normal call again.
    }

    private void requestRate() {
        try {
            String rate = breaker.call(this::fetchExchangeRate);
            System.out.println("Rate: " + rate);
        } catch (CircuitBreakerOpenException exception) {
            System.out.println("Request rejected: circuit breaker is open");
        } catch (Exception exception) {
            System.out.println("Service failed: " + exception.getMessage());
        }
        System.out.println("State after request: " + breaker.currentState());
        System.out.println();
    }

    private String fetchExchangeRate() throws IOException {
        serviceCalls++;
        System.out.println("Service call #" + serviceCalls + ", state: " + breaker.currentState());

        // Simulate an external service failing twice, then recovering.
        if (serviceCalls <= 2) {
            throw new IOException("Exchange rate service is unavailable");
        }
        return "1 EUR = 1.10 USD (demo rate)";
    }
}
