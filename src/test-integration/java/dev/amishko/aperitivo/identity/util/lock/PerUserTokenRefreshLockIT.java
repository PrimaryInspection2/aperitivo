package dev.amishko.aperitivo.identity.util.lock;

import dev.amishko.aperitivo.config.BaseIT;
import dev.amishko.aperitivo.identity.dto.DataProvider;
import dev.amishko.aperitivo.identity.exception.retryable.LockAcquiringException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Basic behavioural tests for {@link PerUserTokenRefreshLock} against a real
 * Redisson-managed Redis (via Testcontainers, wired through {@link BaseIT}).
 *
 * <p>Concurrency scenarios that need multi-thread orchestration live in
 * {@link PerUserTokenRefreshLockConcurrencyIT}. Isolated wiring / error-path
 * behaviour (interrupts, key composition) is covered by the unit test
 * {@link PerUserTokenRefreshLockUnitTest}.
 */
class PerUserTokenRefreshLockIT extends BaseIT {

    @Autowired
    private PerUserTokenRefreshLock refreshLock;

    @Autowired
    private RedissonClient redisson;

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("returns the action result and releases the lock afterwards")
        void returnsResultAndReleasesLock() {
            UUID userId = UUID.randomUUID();

            String result = refreshLock.executeWithLock(DataProvider.STRAVA, userId, () -> "hello");

            assertThat(result).isEqualTo("hello");
            assertThat(lockFor(userId).isLocked()).isFalse();
        }

        @Test
        @DisplayName("reentrant — same thread may re-acquire the same lock through nested calls")
        void reentrantSameThread() {
            UUID userId = UUID.randomUUID();

            Integer depth = refreshLock.executeWithLock(DataProvider.STRAVA, userId, () ->
                    refreshLock.executeWithLock(DataProvider.STRAVA, userId, () -> 2));

            assertThat(depth).isEqualTo(2);
            assertThat(lockFor(userId).isLocked()).isFalse();
        }

        @Test
        @DisplayName("locks for different users do not interfere")
        void differentUsersDoNotBlockEachOther() {
            UUID userA = UUID.randomUUID();
            UUID userB = UUID.randomUUID();

            refreshLock.executeWithLock(DataProvider.STRAVA, userA, () ->
                    refreshLock.executeWithLock(DataProvider.STRAVA, userB, () -> "ok"));
        }
    }

    @Nested
    @DisplayName("failure paths")
    class FailurePaths {

        @Test
        @DisplayName("action throws → exception propagates AND lock is released")
        void propagatesExceptionAndReleasesLock() {
            UUID userId = UUID.randomUUID();

            assertThatThrownBy(() -> refreshLock.executeWithLock(DataProvider.STRAVA, userId, () -> {
                throw new IllegalStateException("boom");
            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("boom");

            assertThat(lockFor(userId).isLocked()).isFalse();
        }

        @Test
        @DisplayName("waitTime exceeded → LockAcquiringException with user + provider in the message")
        void waitTimeExceededThrows() throws Exception {
            UUID userId = UUID.randomUUID();

            // Lock must be held by a DIFFERENT thread — Redisson locks are reentrant per thread.
            CountDownLatch acquired = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService holder = Executors.newSingleThreadExecutor();
            try {
                holder.submit(() -> {
                    RLock held = lockFor(userId);
                    held.lock(10, TimeUnit.SECONDS);
                    acquired.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        if (held.isHeldByCurrentThread()) held.unlock();
                    }
                    return null;
                });
                assertThat(acquired.await(3, TimeUnit.SECONDS)).isTrue();

                assertThatThrownBy(() -> refreshLock.executeWithLock(
                        DataProvider.STRAVA, userId,
                        Duration.ofMillis(200), Duration.ofSeconds(5),
                        () -> "should never run"))
                        .isInstanceOf(LockAcquiringException.class)
                        .hasMessageContaining("Could not acquire")
                        .hasMessageContaining(userId.toString());
            } finally {
                release.countDown();
                holder.shutdown();
                holder.awaitTermination(2, TimeUnit.SECONDS);
            }
        }
    }

    // -----------------------------------------------------------------------

    private RLock lockFor(UUID userId) {
        return redisson.getLock("iam:token-refresh-lock:STRAVA:" + userId);
    }
}
