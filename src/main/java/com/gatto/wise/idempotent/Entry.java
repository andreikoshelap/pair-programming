package com.gatto.wise.idempotent;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

final class Entry<R> {
    final CompletableFuture<R> future = new CompletableFuture<>();
    // Set before successful completion; null while running or after failure.
    volatile Instant expiresAt;
}
