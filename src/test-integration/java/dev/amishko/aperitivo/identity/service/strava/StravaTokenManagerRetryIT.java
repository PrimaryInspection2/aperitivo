package dev.amishko.aperitivo.identity.service.strava;

import dev.amishko.aperitivo.config.BaseIT;
import dev.amishko.aperitivo.identity.TokenManager;
import dev.amishko.aperitivo.identity.dto.ConnectedSourceStatus;
import dev.amishko.aperitivo.identity.dto.DataProvider;
import dev.amishko.aperitivo.identity.dto.RevocationReason;
import dev.amishko.aperitivo.identity.dto.tokens.AccessTokenDto;
import dev.amishko.aperitivo.identity.dto.tokens.RefreshTokenDto;
import dev.amishko.aperitivo.identity.dto.tokens.TokensDto;
import dev.amishko.aperitivo.identity.events.dto.IntegrationRevokedEvent;
import dev.amishko.aperitivo.identity.repository.ConnectedSourceRepository;
import dev.amishko.aperitivo.identity.repository.entity.ConnectedSourceEntity;
import dev.amishko.aperitivo.identity.service.TokenStateManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.AssertablePublishedEvents;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@code @Retryable(ObjectOptimisticLockingFailureException.class)}
 * contract declared on {@link StravaTokenManager#markRevoked} and
 * {@link StravaTokenStateService#persistRefreshed}.
 *
 * <p>Approach: replace {@link ConnectedSourceRepository} with a full
 * {@link MockitoBean}. The surrounding {@code @EnableRetry} AOP proxy — which
 * is the piece we are actually testing — stays intact. A stateful stub
 * ({@code .thenThrow(opt-lock).thenReturn(entity)}) makes attempt 1 fail
 * deterministically and attempt 2 succeed.
 *
 * <p>Rationale for a full mock rather than {@code MockitoSpyBean}: Spring Data
 * proxies are JDK dynamic proxies over abstract interface methods, so
 * {@code Mockito.callRealMethod()} on a spy fails with "Cannot call abstract
 * real method on java object!". A full mock is coupling-free and lets the
 * test run without threads or timing.
 */
class StravaTokenManagerRetryIT extends BaseIT {

    @Autowired
    private TokenManager tokenManager;

    @Autowired
    private TokenStateManager tokenStateManager;

    @Autowired
    private Clock clock;

    @MockitoBean
    private ConnectedSourceRepository connectedSourceRepository;

    // =======================================================================
    //  markRevoked — annotated @Retryable on StravaTokenManager
    // =======================================================================

    @Nested
    @DisplayName("markRevoked")
    class MarkRevoked {

        @Test
        @DisplayName("retries once on ObjectOptimisticLockingFailureException, succeeds on attempt 2")
        void retriesAndSucceeds(AssertablePublishedEvents events) {
            UUID userId = UUID.randomUUID();
            UUID sourceId = UUID.randomUUID();
            ConnectedSourceEntity entity = newActiveEntity(userId, sourceId);

            when(connectedSourceRepository.findByUserIdAndProvider(userId, DataProvider.STRAVA))
                    .thenThrow(new ObjectOptimisticLockingFailureException(
                            ConnectedSourceEntity.class, sourceId))
                    .thenReturn(Optional.of(entity));

            // NOTE: called without an outer transaction wrapper — otherwise attempt 1's
            // opt-lock would mark the outer tx as rollback-only and attempt 2 would fail
            // with "Transaction is marked as rollback-only". @Transactional on markRevoked
            // itself provides the per-attempt boundary.
            tokenManager.markRevoked(userId, RevocationReason.DEAUTHORIZED);

            verify(connectedSourceRepository, times(2))
                    .findByUserIdAndProvider(userId, DataProvider.STRAVA);
            assertThat(entity.getStatus())
                    .as("attempt 2 must actually mutate the entity — retry is not a no-op")
                    .isEqualTo(ConnectedSourceStatus.REVOKED);
            assertThat(entity.getScopes()).isEmpty();

            assertThat(events.ofType(IntegrationRevokedEvent.class))
                    .as("exactly one event must be published — the failed attempt's tx rolled back")
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.userId()).isEqualTo(userId);
                        assertThat(event.connectedSourceId()).isEqualTo(sourceId);
                        assertThat(event.provider()).isEqualTo(DataProvider.STRAVA);
                        assertThat(event.reason()).isEqualTo(RevocationReason.DEAUTHORIZED);
                    });
            assertThat(recordedEvents.integrationRevokedAfterCommit())
                    .as("AFTER_COMMIT consumers observe the retry's committed publication only")
                    .hasSize(1);
        }
    }

    // =======================================================================
    //  persistRefreshed — annotated @Retryable on StravaTokenStateService
    // =======================================================================

    @Nested
    @DisplayName("persistRefreshed")
    class PersistRefreshed {

        @Test
        @DisplayName("retries once on ObjectOptimisticLockingFailureException, succeeds on attempt 2")
        void retriesAndSucceeds() {
            UUID userId = UUID.randomUUID();
            UUID sourceId = UUID.randomUUID();
            ConnectedSourceEntity entity = newActiveEntity(userId, sourceId);

            when(connectedSourceRepository.findByUserIdAndProvider(userId, DataProvider.STRAVA))
                    .thenThrow(new ObjectOptimisticLockingFailureException(
                            ConnectedSourceEntity.class, sourceId))
                    .thenReturn(Optional.of(entity));

            Instant newExpiry = clock.instant().plus(Duration.ofHours(6));
            TokensDto refreshed = new TokensDto(
                    new AccessTokenDto("at-new", newExpiry),
                    new RefreshTokenDto("rt-new", null),
                    List.of("scopes-are-ignored-on-refresh"));

            TokensDto result = tokenStateManager.persistRefreshed(userId, refreshed);

            verify(connectedSourceRepository, times(2))
                    .findByUserIdAndProvider(userId, DataProvider.STRAVA);
            assertThat(result.accessToken().token()).isEqualTo("at-new");
            assertThat(entity.getAccessToken())
                    .as("attempt 2 must persist the new access token onto the entity")
                    .isEqualTo("at-new");
            assertThat(entity.getRefreshToken()).isEqualTo("rt-new");
            assertThat(entity.getLastRefreshedAt()).isNotNull();
            assertThat(entity.getFailureCount()).isZero();
            assertThat(entity.getLastError()).isNull();
        }
    }

    // -----------------------------------------------------------------------

    private ConnectedSourceEntity newActiveEntity(UUID userId, UUID sourceId) {
        return ConnectedSourceEntity.builder()
                .id(sourceId)
                .userId(userId)
                .provider(DataProvider.STRAVA)
                .providerUserId("42")
                .accessToken("at-old")
                .refreshToken("rt-old")
                .accessTokenExpiresAt(clock.instant().plus(Duration.ofHours(1)))
                .status(ConnectedSourceStatus.ACTIVE)
                // ArrayList so markRevoked can clear it in-place.
                .scopes(new ArrayList<>(List.of("read")))
                .failureCount(3)
                .lastError("SERVER_ERROR")
                .build();
    }
}
