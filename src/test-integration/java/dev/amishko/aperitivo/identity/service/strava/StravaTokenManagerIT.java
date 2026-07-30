package dev.amishko.aperitivo.identity.service.strava;

import dev.amishko.aperitivo.config.BaseIT;
import dev.amishko.aperitivo.config.TestFixtures.SeedSpec;
import dev.amishko.aperitivo.identity.TokenManager;
import dev.amishko.aperitivo.identity.dto.ConnectedSourceStatus;
import dev.amishko.aperitivo.identity.dto.DataProvider;
import dev.amishko.aperitivo.identity.dto.RevocationReason;
import dev.amishko.aperitivo.identity.dto.TokenUseFailureKind;
import dev.amishko.aperitivo.identity.dto.tokens.AccessTokenDto;
import dev.amishko.aperitivo.identity.dto.tokens.RefreshTokenDto;
import dev.amishko.aperitivo.identity.dto.tokens.TokensDto;
import dev.amishko.aperitivo.identity.events.dto.IntegrationRevokedEvent;
import dev.amishko.aperitivo.identity.exception.oauth.OAuthBadRequestException;
import dev.amishko.aperitivo.identity.exception.oauth.OAuthClientCredentialsException;
import dev.amishko.aperitivo.identity.exception.oauth.OAuthTransientException;
import dev.amishko.aperitivo.identity.exception.terminal.IntegrationNotActiveException;
import dev.amishko.aperitivo.identity.feign.strava.StravaOAuthClient;
import dev.amishko.aperitivo.identity.repository.ConnectedSourceRepository;
import dev.amishko.aperitivo.identity.repository.entity.ConnectedSourceEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.AssertablePublishedEvents;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for {@link StravaTokenManager} — the sole public port of
 * the identity BC for Strava-token operations.
 *
 * <p>The tests exercise four contracts:
 *
 * <ol>
 *   <li><b>Fast path</b> — still-valid tokens are returned without a Strava
 *       call; a REVOKED source is rejected on the fast path.</li>
 *   <li><b>Refresh path</b> — expired tokens trigger exactly one refresh, and
 *       transport failures (400, 401, 5xx) <b>propagate as-is without a
 *       hidden {@code markRevoked}</b> — this is the C.3 mass-revoke incident
 *       guard. Ingestion is the caller that decides.</li>
 *   <li><b>Concurrency</b> — concurrent expired-token requests dedup inside
 *       the per-user lock via double-check, so only one HTTP call is made;
 *       a revoke that lands between the fast-path read and the inside-lock
 *       re-read is detected as {@code IntegrationNotActiveException}.</li>
 *   <li><b>State-mutation methods</b> — {@code markRevoked} (idempotent,
 *       clears scopes, publishes a well-formed {@code IntegrationRevokedEvent})
 *       and {@code recordSuccessfulUse}/{@code recordFailure} (async on
 *       {@code iamAuditTaskExecutor}; a missing source must be swallowed so
 *       the executor survives).</li>
 * </ol>
 *
 * <p>Event assertions use two independent channels:
 * <ul>
 *   <li>{@link AssertablePublishedEvents} (Modulith-native) — for
 *       "was this event published, and does its payload match?".</li>
 *   <li>{@link dev.amishko.aperitivo.config.RecordedEvents#integrationRevokedAfterCommit()}
 *       — for "did the enclosing transaction actually commit?".</li>
 * </ul>
 */
class StravaTokenManagerIT extends BaseIT {

    @Autowired
    private TokenManager tokenManager;

    @Autowired
    private StravaTokenManager stravaTokenManager;

    @Autowired
    private ConnectedSourceRepository connectedSourceRepository;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private Clock clock;

    @MockitoBean
    private StravaOAuthClient stravaOAuthClient;

    // =======================================================================
    //  getValidAccessToken — fast path
    // =======================================================================

    @Nested
    @DisplayName("getValidAccessToken — fast path")
    class FastPath {

        @Test
        @DisplayName("still-valid token → returns cached, no Strava call")
        void stillValidReturnsCached() {
            UUID userId = fixtures.seedActiveSource();

            AccessTokenDto token = stravaTokenManager.getValidAccessToken(userId);

            assertThat(token.token()).isEqualTo("at-old");
            verify(stravaOAuthClient, never()).refreshAccessToken(any());
        }

        @Test
        @DisplayName("REVOKED source → IntegrationNotActiveException, no Strava call")
        void revokedSourceThrowsFromFastPath() {
            UUID userId = fixtures.seedRevokedSource(clock.instant().plus(Duration.ofHours(1)));

            assertThatThrownBy(() -> stravaTokenManager.getValidAccessToken(userId))
                    .isInstanceOf(IntegrationNotActiveException.class);
            verify(stravaOAuthClient, never()).refreshAccessToken(any());
        }
    }

    // =======================================================================
    //  getValidAccessToken — refresh happy path
    // =======================================================================

    @Nested
    @DisplayName("getValidAccessToken — refresh happy path")
    class RefreshHappyPath {

        @Test
        @DisplayName("expired token → refresh call, new token persisted and returned")
        void expiredTokenTriggersRefresh() {
            UUID userId = fixtures.seedActiveSource(clock.instant().minus(Duration.ofMinutes(5)));

            Instant newExpiry = clock.instant().plus(Duration.ofHours(6)).truncatedTo(ChronoUnit.MICROS);
            when(stravaOAuthClient.refreshAccessToken("rt-old"))
                    .thenReturn(new TokensDto(
                            new AccessTokenDto("at-new", newExpiry),
                            new RefreshTokenDto("rt-new", null),
                            List.of("read")));

            AccessTokenDto token = stravaTokenManager.getValidAccessToken(userId);

            assertThat(token.token()).isEqualTo("at-new");
            assertThat(token.expiresAt()).isEqualTo(newExpiry);

            ConnectedSourceEntity reloaded = loadSource(userId);
            assertThat(reloaded.getAccessToken()).isEqualTo("at-new");
            assertThat(reloaded.getRefreshToken()).isEqualTo("rt-new");
            assertThat(reloaded.getAccessTokenExpiresAt()).isEqualTo(newExpiry);
            assertThat(reloaded.getLastRefreshedAt()).isNotNull();
            verify(stravaOAuthClient, times(1)).refreshAccessToken("rt-old");
        }
    }

    // =======================================================================
    //  getValidAccessToken — refresh failures MUST propagate (C.3 guard)
    // =======================================================================

    @Nested
    @DisplayName("getValidAccessToken — refresh failure propagation (C.3 guard)")
    class RefreshFailurePropagation {

        @Test
        @DisplayName("400 (invalid_grant) → OAuthBadRequestException propagates, source stays ACTIVE, no event")
        void badRequestPropagatesWithoutHiddenRevoke(AssertablePublishedEvents events) {
            UUID userId = fixtures.seedSource(SeedSpec.builder()
                    .accessToken("at-old").refreshToken("rt-dead")
                    .accessTokenExpiresAt(clock.instant().minus(Duration.ofMinutes(5)))
                    .status(ConnectedSourceStatus.ACTIVE)
                    .build());

            when(stravaOAuthClient.refreshAccessToken("rt-dead"))
                    .thenThrow(new OAuthBadRequestException("Strava rejected refresh: invalid_grant"));

            assertThatThrownBy(() -> stravaTokenManager.getValidAccessToken(userId))
                    .isInstanceOf(OAuthBadRequestException.class);

            assertThat(loadSource(userId).getStatus())
                    .as("IAM must NOT hide-revoke on refresh failure — caller (Ingestion) decides")
                    .isEqualTo(ConnectedSourceStatus.ACTIVE);
            assertThat(events.ofType(IntegrationRevokedEvent.class))
                    .as("no IntegrationRevokedEvent must be published from getValidAccessToken")
                    .isEmpty();
        }

        @Test
        @DisplayName("401 (client credentials) → OAuthClientCredentialsException propagates, no revoke, no event")
        void clientCredentialsPropagatesWithoutRevoke(AssertablePublishedEvents events) {
            UUID userId = fixtures.seedActiveSource(clock.instant().minus(Duration.ofMinutes(5)));

            when(stravaOAuthClient.refreshAccessToken("rt-old"))
                    .thenThrow(new OAuthClientCredentialsException("our client_secret is broken"));

            assertThatThrownBy(() -> stravaTokenManager.getValidAccessToken(userId))
                    .isInstanceOf(OAuthClientCredentialsException.class);

            assertThat(loadSource(userId).getStatus())
                    .as("systemic 401 must NEVER mass-revoke users — this is the C.3 incident guard")
                    .isEqualTo(ConnectedSourceStatus.ACTIVE);
            assertThat(events.ofType(IntegrationRevokedEvent.class)).isEmpty();
        }

        @Test
        @DisplayName("5xx (transient) → OAuthTransientException propagates, source stays ACTIVE, no event")
        void transientPropagatesWithoutRevoke(AssertablePublishedEvents events) {
            UUID userId = fixtures.seedActiveSource(clock.instant().minus(Duration.ofMinutes(5)));

            when(stravaOAuthClient.refreshAccessToken("rt-old"))
                    .thenThrow(new OAuthTransientException("503 from Strava"));

            assertThatThrownBy(() -> stravaTokenManager.getValidAccessToken(userId))
                    .isInstanceOf(OAuthTransientException.class);

            assertThat(loadSource(userId).getStatus()).isEqualTo(ConnectedSourceStatus.ACTIVE);
            assertThat(events.ofType(IntegrationRevokedEvent.class)).isEmpty();
        }
    }

    // =======================================================================
    //  getValidAccessToken — concurrency + revoke race
    // =======================================================================

    @Nested
    @DisplayName("getValidAccessToken — concurrency")
    class Concurrency {

        @Test
        @DisplayName("source becomes REVOKED between fast-path read and inside-lock re-read → IntegrationNotActiveException, no Strava call")
        void revokeBetweenFastPathAndLockReReadIsDetected() {
            UUID userId = fixtures.seedActiveSource(clock.instant().minus(Duration.ofMinutes(5)));

            // Simulate the deauth webhook winning the race: the source is flipped
            // to REVOKED after the fast path read the ACTIVE state but before the
            // lock's double-check re-reads it.
            tx.executeWithoutResult(status -> {
                ConnectedSourceEntity src = loadSource(userId);
                src.setStatus(ConnectedSourceStatus.REVOKED);
                connectedSourceRepository.saveAndFlush(src);
            });

            assertThatThrownBy(() -> stravaTokenManager.getValidAccessToken(userId))
                    .as("double-check readSnapshot inside the lock must reject a mid-flight revoke")
                    .isInstanceOf(IntegrationNotActiveException.class);
            verify(stravaOAuthClient, never()).refreshAccessToken(any());
        }

        @Test
        @DisplayName("concurrent expired-token requests → only ONE Strava refresh (double-check dedups)")
        void concurrentRefreshDedupsViaDoubleCheck() throws Exception {
            UUID userId = fixtures.seedActiveSource(clock.instant().minus(Duration.ofMinutes(5)));

            AtomicInteger stravaCalls = new AtomicInteger();
            CountDownLatch firstThreadInsideRefresh = new CountDownLatch(1);
            CountDownLatch letRefreshProceed = new CountDownLatch(1);

            Instant newExpiry = clock.instant().plus(Duration.ofHours(6));
            when(stravaOAuthClient.refreshAccessToken(any())).thenAnswer(inv -> {
                stravaCalls.incrementAndGet();
                // Signal that thread 1 is inside the refresh (i.e. holds the
                // per-user lock) — only then does the test launch thread 2, so
                // thread 2 deterministically blocks on the lock.
                firstThreadInsideRefresh.countDown();
                letRefreshProceed.await(3, TimeUnit.SECONDS);
                return new TokensDto(
                        new AccessTokenDto("at-new", newExpiry),
                        new RefreshTokenDto("rt-new", null),
                        List.of("read"));
            });

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                var f1 = pool.submit(() -> stravaTokenManager.getValidAccessToken(userId));

                assertThat(firstThreadInsideRefresh.await(3, TimeUnit.SECONDS))
                        .as("thread 1 must reach the refresh call before we start thread 2")
                        .isTrue();

                var f2 = pool.submit(() -> stravaTokenManager.getValidAccessToken(userId));
                letRefreshProceed.countDown();

                assertThat(f1.get(5, TimeUnit.SECONDS).token()).isEqualTo("at-new");
                assertThat(f2.get(5, TimeUnit.SECONDS).token()).isEqualTo("at-new");
            } finally {
                pool.shutdownNow();
            }

            assertThat(stravaCalls.get())
                    .as("double-check inside the lock must dedup the refresh — only one HTTP call to Strava")
                    .isEqualTo(1);
        }
    }

    // =======================================================================
    //  markRevoked
    // =======================================================================

    @Nested
    @DisplayName("markRevoked")
    class MarkRevoked {

        @Test
        @DisplayName("flips status to REVOKED, clears scopes, publishes a well-formed IntegrationRevokedEvent")
        void marksRevokedAndPublishesEvent(AssertablePublishedEvents events) {
            UUID userId = fixtures.seedActiveSource();
            UUID sourceId = loadSource(userId).getId();

            tokenManager.markRevoked(userId, RevocationReason.USER_REQUESTED);

            ConnectedSourceEntity reloaded = loadSource(userId);
            assertThat(reloaded.getStatus()).isEqualTo(ConnectedSourceStatus.REVOKED);
            assertThat(reloaded.getScopes())
                    .as("markRevoked must clear scopes to prevent stale-privilege leaks after reconnect")
                    .isEmpty();

            assertThat(events.ofType(IntegrationRevokedEvent.class))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.userId()).isEqualTo(userId);
                        assertThat(event.connectedSourceId()).isEqualTo(sourceId);
                        assertThat(event.provider()).isEqualTo(DataProvider.STRAVA);
                        assertThat(event.reason()).isEqualTo(RevocationReason.USER_REQUESTED);
                        assertThat(event.occurredAt())
                                .isNotNull()
                                .isBeforeOrEqualTo(clock.instant().plus(Duration.ofSeconds(1)));
                    });

            assertThat(recordedEvents.integrationRevokedAfterCommit())
                    .as("AFTER_COMMIT consumers must observe the same event once the tx commits")
                    .hasSize(1);
        }

        @Test
        @DisplayName("second call on an already-REVOKED source is a no-op — no second event")
        void isIdempotent(AssertablePublishedEvents events) {
            UUID userId = fixtures.seedActiveSource();

            tokenManager.markRevoked(userId, RevocationReason.USER_REQUESTED);
            tokenManager.markRevoked(userId, RevocationReason.DEAUTHORIZED);

            assertThat(events.ofType(IntegrationRevokedEvent.class))
                    .as("event storms on duplicate webhooks are the exact bug this idempotency guards")
                    .hasSize(1);
            assertThat(events.ofType(IntegrationRevokedEvent.class).iterator().next().reason())
                    .as("the first-cause reason wins — later duplicate webhooks must not overwrite it")
                    .isEqualTo(RevocationReason.USER_REQUESTED);
        }
    }

    // =======================================================================
    //  Async audit methods — must never crash the executor
    // =======================================================================

    @Nested
    @DisplayName("recordSuccessfulUse / recordFailure — async audit path")
    class AsyncAudit {

        @Test
        @DisplayName("recordSuccessfulUse updates lastUsedAt")
        void recordSuccessfulUseHappyPath() {
            UUID userId = fixtures.seedActiveSource();

            tokenManager.recordSuccessfulUse(userId);

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(loadSource(userId).getLastUsedAt()).isNotNull());
        }

        @Test
        @DisplayName("recordFailure increments failureCount and stores kind")
        void recordFailureHappyPath() {
            UUID userId = fixtures.seedActiveSource();

            tokenManager.recordFailure(userId, TokenUseFailureKind.SERVER_ERROR);

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                ConnectedSourceEntity reloaded = loadSource(userId);
                assertThat(reloaded.getFailureCount()).isEqualTo(1);
                assertThat(reloaded.getLastError()).isEqualTo(TokenUseFailureKind.SERVER_ERROR.name());
            });
        }

        @Test
        @DisplayName("recordSuccessfulUse for a missing source — swallowed, executor thread survives next call")
        void recordSuccessfulUseSwallowsMissingSource() {
            tokenManager.recordSuccessfulUse(UUID.randomUUID());

            UUID realUser = fixtures.seedActiveSource();
            tokenManager.recordSuccessfulUse(realUser);

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(loadSource(realUser).getLastUsedAt())
                            .as("post-swallow, executor must still process the next audit task")
                            .isNotNull());
        }

        @Test
        @DisplayName("recordFailure for a missing source — swallowed, executor thread survives next call")
        void recordFailureSwallowsMissingSource() {
            tokenManager.recordFailure(UUID.randomUUID(), TokenUseFailureKind.NETWORK_ERROR);

            UUID realUser = fixtures.seedActiveSource();
            tokenManager.recordFailure(realUser, TokenUseFailureKind.NETWORK_ERROR);

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(loadSource(realUser).getFailureCount()).isEqualTo(1));
        }
    }

    // -----------------------------------------------------------------------

    private ConnectedSourceEntity loadSource(UUID userId) {
        return connectedSourceRepository
                .findByUserIdAndProvider(userId, DataProvider.STRAVA)
                .orElseThrow();
    }
}
