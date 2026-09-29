package com.gatto.wise.rate;

public interface RateLimiter {
    /**
     * Returns true if the call is allowed to proceed, false if it should be rejected.
     */
    boolean tryAcquire(String clientId);
}
