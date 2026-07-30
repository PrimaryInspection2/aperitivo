package dev.amishko.aperitivo.identity.util.lock;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for {@link PerUserTokenRefreshLock}.
 *
 * <ul>
 *   <li>{@code wait}  — how long a thread will block waiting for the lock
 *       before giving up with {@code TokenRefreshLockException}.</li>
 *   <li>{@code lease} — hard TTL Redisson sets on the lock key, so a crashed
 *       JVM cannot leave the lock held forever. Note: because
 *       {@link org.redisson.api.RLock#tryLock(long, long, java.util.concurrent.TimeUnit)}
 *       is called with an explicit {@code leaseTime}, Redisson's watchdog is
 *       <b>disabled</b> — {@code leaseTime} is a hard ceiling, not a
 *       renewable base. The Strava OAuth HTTP timeout budget
 *       ({@code spring.cloud.openfeign.client.config.strava-oauth.connect-timeout}
 *       + {@code read-timeout}) MUST fit inside {@code leaseTime} with a safety
 *       margin, otherwise the lock can expire mid-refresh and a second thread
 *       will enter the critical section concurrently.</li>
 * </ul>
 *
 * <p>Bound from {@code aperitivo.identity.token-refresh-lock.*} in
 * {@code application.yaml}.
 */
@ConfigurationProperties(prefix = "aperitivo.identity.token-refresh-lock")
public record TokenRefreshLockProperties(Duration waitTime, Duration leaseTime) {

    public TokenRefreshLockProperties {
        if (waitTime == null || waitTime.isNegative() || waitTime.isZero()) {
            throw new IllegalArgumentException(
                    "aperitivo.identity.token-refresh-lock.wait-time must be a positive duration");
        }
        if (leaseTime == null || leaseTime.isNegative() || leaseTime.isZero()) {
            throw new IllegalArgumentException(
                    "aperitivo.identity.token-refresh-lock.lease-time must be a positive duration");
        }
    }
}
