package com.gatto.wise.braker;

public class CircuitBreakerOpenException extends RuntimeException {
    public CircuitBreakerOpenException() {
        super("Circuit breaker is open");
    }
}
