package dev.amishko.aperitivo.identity.util.lock;

import dev.amishko.aperitivo.config.BaseIT;
import dev.amishko.aperitivo.identity.dto.DataProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic multi-thread tests for {@link PerUserTokenRefreshLock}.
 *
 * <p>Every thread interleaving is orchestrated through {@link CountDownLatch}
 * handshakes — there is no {@code Thread.sleep}, so these tests do not become
 * flakier under CI load.
 */
class PerUserTokenRefreshLockConcurrencyIT extends BaseIT {

    @Autowired
    private PerUserTokenRefreshLock refreshLock;

    @Test
    @DisplayName("two threads on the same key — only one is inside the critical section at any moment")
    void mutualExclusionOnSameKey() throws Exception {
        UUID userId = UUID.randomUUID();
        AtomicInteger insideCount = new AtomicInteger();
        AtomicInteger maxObservedInside = new AtomicInteger();
        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> f1 = pool.submit(() -> refreshLock.executeWithLock(
                    DataProvider.STRAVA, userId,
                    Duration.ofSeconds(5), Duration.ofSeconds(10),
                    () -> {
                        int now = insideCount.incrementAndGet();
                        maxObservedInside.accumulateAndGet(now, Math::max);
                        firstAcquired.countDown();
                        bothStarted.countDown();
                        try {
                            releaseFirst.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        insideCount.decrementAndGet();
                        return 1;
                    }));

            // Deterministically wait until thread 1 is *inside* the critical section.
            assertThat(firstAcquired.await(3, TimeUnit.SECONDS))
                    .as("thread 1 must enter the critical section before we launch thread 2")
                    .isTrue();

            Future<Integer> f2 = pool.submit(() -> refreshLock.executeWithLock(
                    DataProvider.STRAVA, userId,
                    Duration.ofSeconds(5), Duration.ofSeconds(10),
                    () -> {
                        int now = insideCount.incrementAndGet();
                        maxObservedInside.accumulateAndGet(now, Math::max);
                        bothStarted.countDown();
                        insideCount.decrementAndGet();
                        return 2;
                    }));

            releaseFirst.countDown();
            assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(f1.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(f2.get(5, TimeUnit.SECONDS)).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }

        assertThat(maxObservedInside.get())
                .as("two threads must never be inside the same-key critical section simultaneously")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("different users enter their critical sections in parallel")
    void differentUsersRunInParallel() throws Exception {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        CountDownLatch bothInside = new CountDownLatch(2);
        AtomicInteger observedInside = new AtomicInteger();
        AtomicInteger maxSimultaneous = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> fA = pool.submit(() -> runAndSignalParallelism(userA, bothInside, observedInside, maxSimultaneous));
            Future<?> fB = pool.submit(() -> runAndSignalParallelism(userB, bothInside, observedInside, maxSimultaneous));
            fA.get(5, TimeUnit.SECONDS);
            fB.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(maxSimultaneous.get())
                .as("different user locks must not serialize")
                .isEqualTo(2);
    }

    // -----------------------------------------------------------------------

    private Object runAndSignalParallelism(UUID userId,
                                           CountDownLatch bothInside,
                                           AtomicInteger observedInside,
                                           AtomicInteger maxSimultaneous) {
        return refreshLock.executeWithLock(DataProvider.STRAVA, userId,
                Duration.ofSeconds(5), Duration.ofSeconds(10),
                () -> {
                    int now = observedInside.incrementAndGet();
                    maxSimultaneous.accumulateAndGet(now, Math::max);
                    bothInside.countDown();
                    try {
                        // Wait until the other thread has also entered, proving parallelism.
                        assertThat(bothInside.await(3, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    observedInside.decrementAndGet();
                    return null;
                });
    }
}
