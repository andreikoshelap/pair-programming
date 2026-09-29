package com.gatto.wise.idempotent;

import com.gatto.wise.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotentProcessorTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-28T12:00:00Z"));
    private final Duration ttl = Duration.ofSeconds(10);
    private final IdempotentProcessor<String> processor =
            new IdempotentProcessor<>(clock, ttl, Duration.ofSeconds(5));

    @Test
    void cachesResultsPerKeyIncludingNull() {
        assertThat(processor.process("a", () -> "first")).isEqualTo("first");
        assertThat(processor.process("a", () -> "second")).isEqualTo("first");
        assertThat(processor.process("b", () -> "other")).isEqualTo("other");
        assertThat(processor.process("null", () -> null)).isNull();
        assertThat(processor.process("null", () -> "replacement")).isNull();
    }

    @Test
    void expiresExactlyOneTtlAfterSuccessfulCompletion() {
        assertThat(processor.process("a", () -> {
            clock.advance(ttl.multipliedBy(2));
            return "first";
        })).isEqualTo("first");

        clock.advance(ttl.minusNanos(1));
        assertThat(processor.process("a", () -> "too early")).isEqualTo("first");
        clock.advance(Duration.ofNanos(1));
        assertThat(processor.process("a", () -> "new")).isEqualTo("new");
    }

    @Test
    void timeoutDoesNotEvictAnInFlightActionEvenAfterTtl() throws Exception {
        var shortWait = new IdempotentProcessor<String>(clock, ttl, Duration.ofMillis(20));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var owner = executor.submit(() -> shortWait.process("a", () -> {
                started.countDown();
                await(release);
                return "original";
            }));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                clock.advance(ttl.multipliedBy(2));
                for (int i = 0; i < 2; i++) {
                    assertThatThrownBy(() -> shortWait.process("a", () -> {
                        throw new AssertionError("In-flight action must not be replaced");
                    })).isInstanceOf(IllegalStateException.class)
                            .hasCauseInstanceOf(TimeoutException.class);
                }
            } finally {
                release.countDown();
            }
            assertThat(owner.get(5, TimeUnit.SECONDS)).isEqualTo("original");
            assertThat(shortWait.process("a", () -> "new")).isEqualTo("original");
        }
    }

    @Test
    void concurrentDuplicateSharesTheOriginalResult() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var owner = executor.submit(() -> processor.process("a", () -> {
                calls.incrementAndGet();
                started.countDown();
                await(release);
                return "original";
            }));
            var duplicate = new FutureTask<>(() -> processor.process("a", () -> {
                calls.incrementAndGet();
                return "duplicate";
            }));
            var waiter = new Thread(duplicate);
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                waiter.start();
                awaitWaiting(waiter);
            } finally {
                release.countDown();
            }
            assertThat(owner.get(5, TimeUnit.SECONDS)).isEqualTo("original");
            assertThat(duplicate.get(5, TimeUnit.SECONDS)).isEqualTo("original");
            assertThat(calls.get()).isEqualTo(1);
            waiter.join(5000);
        }
    }

    @Test
    void duplicateRetriesAfterFailureWithItsOwnRetryBudget() throws Exception {
        // More independent calls than MAX_RETRY must still each be allowed to retry.
        for (int i = 0; i < 7; i++) {
            String key = "key-" + i;
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var failure = new IllegalStateException("failed attempt");
            try (var executor = Executors.newSingleThreadExecutor()) {
                var owner = executor.submit(() -> processor.process(key, () -> {
                    started.countDown();
                    await(release);
                    throw failure;
                }));
                var duplicate = new FutureTask<>(() -> processor.process(key, () -> "retried"));
                var waiter = new Thread(duplicate);
                try {
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                    waiter.start();
                    awaitWaiting(waiter);
                } finally {
                    release.countDown();
                }
                assertThatThrownBy(() -> owner.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCause(failure);
                assertThat(duplicate.get(5, TimeUnit.SECONDS)).isEqualTo("retried");
                waiter.join(5000);
            }
        }
    }

    @Test
    void failedOwnerAllowsALaterCallToRetry() {
        var failure = new IllegalStateException("failed attempt");
        assertThatThrownBy(() -> processor.process("a", () -> { throw failure; }))
                .isSameAs(failure);
        assertThat(processor.process("a", () -> "retried")).isEqualTo("retried");
    }

    @Test
    void interruptedWaitRestoresInterruptFlagAndPreservesOwner() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interruptRestored = new AtomicBoolean();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var owner = executor.submit(() -> processor.process("a", () -> {
                started.countDown();
                await(release);
                return "original";
            }));
            var duplicate = new FutureTask<>(() -> {
                try {
                    return processor.process("a", () -> "duplicate");
                } finally {
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                }
            });
            var waiter = new Thread(duplicate);
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                waiter.start();
                awaitWaiting(waiter);
                waiter.interrupt();
                assertThatThrownBy(() -> duplicate.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasRootCauseInstanceOf(InterruptedException.class);
                assertThat(interruptRestored.get()).isTrue();
            } finally {
                release.countDown();
            }
            assertThat(owner.get(5, TimeUnit.SECONDS)).isEqualTo("original");
            assertThat(processor.process("a", () -> "new")).isEqualTo("original");
            waiter.join(5000);
        }
    }

    @Test
    void validatesArguments() {
        assertThatThrownBy(() -> new IdempotentProcessor<>(null, ttl, ttl))
                .isInstanceOf(NullPointerException.class).hasMessage("clock");
        assertThatThrownBy(() -> new IdempotentProcessor<>(clock, null, ttl))
                .isInstanceOf(NullPointerException.class).hasMessage("keyTtl");
        assertThatThrownBy(() -> new IdempotentProcessor<>(clock, ttl, null))
                .isInstanceOf(NullPointerException.class).hasMessage("waitTimeout");
        for (Duration invalid : new Duration[] {Duration.ZERO, Duration.ofNanos(-1)}) {
            assertThatThrownBy(() -> new IdempotentProcessor<>(clock, invalid, ttl))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new IdempotentProcessor<>(clock, ttl, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> processor.process(null, () -> "value"))
                .isInstanceOf(NullPointerException.class).hasMessage("idempotencyKey");
        assertThatThrownBy(() -> processor.process("a", null))
                .isInstanceOf(NullPointerException.class).hasMessage("action");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test coordination");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
    }

    private static void awaitWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.TIMED_WAITING
                && thread.getState() != Thread.State.WAITING
                && thread.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(thread.getState()).isIn(Thread.State.TIMED_WAITING, Thread.State.WAITING);
    }
}
