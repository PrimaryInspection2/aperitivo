package dev.amishko.aperitivo.identity.util.lock;

import dev.amishko.aperitivo.identity.dto.DataProvider;
import dev.amishko.aperitivo.identity.exception.retryable.LockAcquiringException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
@Slf4j
public class PerUserTokenRefreshLock {

    private static final String LOCK_KEY_PREFIX = "iam:token-refresh-lock";

    private final RedissonClient redisson;
    private final TokenRefreshLockProperties properties;

    public <T> T executeWithLock(DataProvider provider, UUID userId, Supplier<T> action) {
        return executeWithLock(provider, userId, properties.waitTime(), properties.leaseTime(), action);
    }

    public <T> T executeWithLock(DataProvider provider,
                                 UUID userId,
                                 Duration waitTime,
                                 Duration leaseTime,
                                 Supplier<T> action) {
        String key = key(provider, userId);
        RLock lock = redisson.getLock(key);

        boolean acquired;
        try {
            acquired = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquiringException(
                    "Interrupted while acquiring token-refresh lock for user=" + userId
                            + ", provider=" + provider, e);
        }

        if (!acquired) {
            throw new LockAcquiringException(
                    "Could not acquire token-refresh lock within " + waitTime
                            + " for user=" + userId + ", provider=" + provider);
        }

        log.debug("Acquired token-refresh lock (user={}, provider={})", userId, provider);
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("Released token-refresh lock (user={}, provider={})", userId, provider);
            } else {
                log.warn("Token-refresh lock was no longer held on release (lease expired?) "
                        + "(user={}, provider={})", userId, provider);
            }
        }
    }

    private static String key(DataProvider provider, UUID userId) {
        return "%s:%s:%s".formatted(LOCK_KEY_PREFIX, provider.name(), userId);
    }
}
