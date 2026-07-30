package dev.amishko.aperitivo.identity.util.lock;

import dev.amishko.aperitivo.identity.dto.DataProvider;
import dev.amishko.aperitivo.identity.exception.retryable.LockAcquiringException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fast unit tests for {@link PerUserTokenRefreshLock} against a mocked
 * {@link RedissonClient} — covers wiring and error paths that the Redis-backed
 * IT ({@code PerUserTokenRefreshLockIT}) cannot easily exercise
 * deterministically (interrupts, acquisition failures, key composition).
 *
 * <p>All Redis interactions are stubbed; nothing here binds to a real
 * Redisson client, so these run in the ordinary {@code test} phase (surefire),
 * not {@code verify}.
 */
@ExtendWith(MockitoExtension.class)
class PerUserTokenRefreshLockUnitTest {

    private static final Duration WAIT = Duration.ofSeconds(2);
    private static final Duration LEASE = Duration.ofSeconds(10);

    @Mock
    private RedissonClient redisson;

    @Mock
    private RLock rLock;

    private PerUserTokenRefreshLock refreshLock;

    private PerUserTokenRefreshLock newLock() {
        return new PerUserTokenRefreshLock(redisson, new TokenRefreshLockProperties(WAIT, LEASE));
    }

    // -----------------------------------------------------------------------
    // Key composition
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("key composition")
    class KeyComposition {

        @Test
        @DisplayName("lock key is 'iam:token-refresh-lock:{PROVIDER}:{userId}'")
        void keyIncludesProviderAndUserId() throws Exception {
            refreshLock = newLock();
            UUID userId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            when(redisson.getLock(keyCaptor.capture())).thenReturn(rLock);
            when(rLock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
            when(rLock.isHeldByCurrentThread()).thenReturn(true);

            refreshLock.executeWithLock(DataProvider.STRAVA, userId, () -> "ok");

            assertThat(keyCaptor.getValue())
                    .isEqualTo("iam:token-refresh-lock:STRAVA:11111111-2222-3333-4444-555555555555");
        }
    }

    // -----------------------------------------------------------------------
    // Acquisition — the contract-critical failure modes
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("acquisition failure modes")
    class AcquisitionFailures {

        @Test
        @DisplayName("tryLock returns false → LockAcquiringException with user + provider + wait context; action NOT invoked")
        void waitTimeExceededThrowsAndDoesNotRunAction() throws Exception {
            refreshLock = newLock();
            UUID userId = UUID.randomUUID();
            when(redisson.getLock(anyString())).thenReturn(rLock);
            when(rLock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(false);

            AtomicBoolean actionRan = new AtomicBoolean();

            assertThatThrownBy(() -> refreshLock.executeWithLock(
                    DataProvider.STRAVA, userId, () -> {
                        actionRan.set(true);
                        return "never";
                    }))
                    .isInstanceOf(LockAcquiringException.class)
                    .hasMessageContaining("Could not acquire")
                    .hasMessageContaining(userId.toString())
                    .hasMessageContaining("STRAVA");

            assertThat(actionRan).as("action must not run when lock was not acquired").isFalse();
            verify(rLock, never()).unlock();
        }

        @Test
        @DisplayName("tryLock throws InterruptedException → LockAcquiringException wraps it and interrupt flag is restored")
        void interruptedWhileAcquiringRestoresInterruptFlagAndWraps() throws Exception {
            refreshLock = newLock();
            UUID userId = UUID.randomUUID();
            InterruptedException cause = new InterruptedException("blocked-wait interrupted");
            when(redisson.getLock(anyString())).thenReturn(rLock);
            when(rLock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenThrow(cause);

            AtomicBoolean actionRan = new AtomicBoolean();

            try {
                // Preconditions: clear interrupt flag so we can observe restoration cleanly.
                assertThat(Thread.interrupted()).isFalse();

                assertThatThrownBy(() -> refreshLock.executeWithLock(
                        DataProvider.STRAVA, userId, () -> {
                            actionRan.set(true);
                            return "never";
                        }))
                        .isInstanceOf(LockAcquiringException.class)
                        .hasMessageContaining("Interrupted")
                        .hasMessageContaining(userId.toString())
                        .hasCause(cause);

                assertThat(Thread.currentThread().isInterrupted())
                        .as("interrupt flag must be restored so upstream code can observe cancellation")
                        .isTrue();
            } finally {
                // Do not leak an interrupted state into sibling tests on this thread.
                Thread.interrupted();
            }

            assertThat(actionRan).isFalse();
            verify(rLock, never()).unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Lock release — must happen even when the action throws
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("lock release")
    class LockRelease {

        @Test
        @DisplayName("action throws → exception propagates, unlock() is still called once")
        void actionExceptionStillReleasesLock() throws Exception {
            refreshLock = newLock();
            UUID userId = UUID.randomUUID();
            when(redisson.getLock(anyString())).thenReturn(rLock);
            when(rLock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
            when(rLock.isHeldByCurrentThread()).thenReturn(true);

            assertThatThrownBy(() -> refreshLock.executeWithLock(
                    DataProvider.STRAVA, userId, () -> {
                        throw new IllegalStateException("boom");
                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("boom");

            verify(rLock, times(1)).unlock();
        }

        @Test
        @DisplayName("lease expired mid-action → unlock() is NOT called (isHeldByCurrentThread=false), no crash")
        void leaseExpiredDoesNotCallUnlock() throws Exception {
            refreshLock = newLock();
            UUID userId = UUID.randomUUID();
            when(redisson.getLock(anyString())).thenReturn(rLock);
            when(rLock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
            when(rLock.isHeldByCurrentThread()).thenReturn(false);

            String result = refreshLock.executeWithLock(DataProvider.STRAVA, userId, () -> "ok");

            assertThat(result).isEqualTo("ok");
            verify(rLock, never()).unlock();
        }
    }

    // -----------------------------------------------------------------------
    // Explicit-timeout overload — defaults are overridable per call
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("explicit-timeout overload passes millis of the caller-supplied durations to tryLock")
    void explicitTimeoutOverloadPassesDurations() throws Exception {
        refreshLock = newLock();
        UUID userId = UUID.randomUUID();
        when(redisson.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        refreshLock.executeWithLock(
                DataProvider.STRAVA, userId,
                Duration.ofMillis(750), Duration.ofSeconds(3),
                () -> "ok");

        verify(rLock).tryLock(750L, 3_000L, TimeUnit.MILLISECONDS);
    }
}
