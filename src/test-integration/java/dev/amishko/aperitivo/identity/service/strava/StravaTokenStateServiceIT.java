package dev.amishko.aperitivo.identity.service.strava;

import dev.amishko.aperitivo.config.BaseIT;
import dev.amishko.aperitivo.config.TestFixtures.SeedSpec;
import dev.amishko.aperitivo.identity.dto.ConnectedSourceStatus;
import dev.amishko.aperitivo.identity.dto.DataProvider;
import dev.amishko.aperitivo.identity.dto.tokens.AccessTokenDto;
import dev.amishko.aperitivo.identity.dto.tokens.RefreshTokenDto;
import dev.amishko.aperitivo.identity.dto.tokens.TokensDto;
import dev.amishko.aperitivo.identity.exception.terminal.ConnectedSourceNotFoundException;
import dev.amishko.aperitivo.identity.exception.terminal.IntegrationNotActiveException;
import dev.amishko.aperitivo.identity.repository.ConnectedSourceRepository;
import dev.amishko.aperitivo.identity.repository.entity.ConnectedSourceEntity;
import dev.amishko.aperitivo.identity.service.TokenStateManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link StravaTokenStateService} — the JPA-backed
 * {@link TokenStateManager} implementation sitting directly below
 * {@link StravaTokenManager}.
 *
 * <p>These tests cover contracts that {@code StravaTokenManagerIT} exercises
 * only obliquely:
 *
 * <ul>
 *   <li>{@code readSnapshot} — ACTIVE returns full token data; REVOKED throws
 *       {@link IntegrationNotActiveException}; missing source throws
 *       {@link ConnectedSourceNotFoundException}.</li>
 *   <li>{@code persistRefreshed} — field-by-field write behaviour (access +
 *       refresh tokens, expiries, {@code lastRefreshedAt}, error reset,
 *       failure-counter reset), scope preservation across refresh, nullable
 *       {@code refresh_token_expires_at}, and the REVOKED gate.</li>
 * </ul>
 */
class StravaTokenStateServiceIT extends BaseIT {

    @Autowired
    private TokenStateManager tokenStateManager;

    @Autowired
    private ConnectedSourceRepository connectedSourceRepository;

    @Autowired
    private Clock clock;

    // =======================================================================
    //  readSnapshot
    // =======================================================================

    @Nested
    @DisplayName("readSnapshot")
    class ReadSnapshot {

        @Test
        @DisplayName("ACTIVE source → returns tokens with scopes preserved")
        void activeReturnsTokens() {
            Instant accessExpiry = clock.instant().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
            Instant refreshExpiry = clock.instant().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.MICROS);
            UUID userId = fixtures.seedSource(SeedSpec.builder()
                    .accessToken("at").refreshToken("rt")
                    .accessTokenExpiresAt(accessExpiry).refreshTokenExpiresAt(refreshExpiry)
                    .status(ConnectedSourceStatus.ACTIVE)
                    .scopes(List.of("read", "activity:read"))
                    .build());

            TokensDto snapshot = tokenStateManager.readSnapshot(userId);

            assertThat(snapshot.accessToken().token()).isEqualTo("at");
            assertThat(snapshot.accessToken().expiresAt()).isEqualTo(accessExpiry);
            assertThat(snapshot.refreshToken().token()).isEqualTo("rt");
            assertThat(snapshot.refreshToken().expiresAt()).isEqualTo(refreshExpiry);
            assertThat(snapshot.scopes()).containsExactly("read", "activity:read");
        }

        @Test
        @DisplayName("REVOKED source → IntegrationNotActiveException")
        void revokedThrows() {
            UUID userId = fixtures.seedRevokedSource(clock.instant().plus(Duration.ofHours(1)));

            assertThatThrownBy(() -> tokenStateManager.readSnapshot(userId))
                    .isInstanceOf(IntegrationNotActiveException.class);
        }

        @Test
        @DisplayName("no connected source for user → ConnectedSourceNotFoundException")
        void missingSourceThrows() {
            assertThatThrownBy(() -> tokenStateManager.readSnapshot(UUID.randomUUID()))
                    .isInstanceOf(ConnectedSourceNotFoundException.class)
                    .hasMessageContaining("STRAVA");
        }
    }

    // =======================================================================
    //  persistRefreshed
    // =======================================================================

    @Nested
    @DisplayName("persistRefreshed")
    class PersistRefreshed {

        @Test
        @DisplayName("ACTIVE source → tokens/expiries/lastRefreshedAt updated; error+counter reset; scopes untouched")
        void happyPathUpdatesAllExpectedFields() {
            List<String> originalScopes = List.of("read", "activity:read");
            UUID userId = fixtures.seedSource(SeedSpec.builder()
                    .accessToken("at-old").refreshToken("rt-old")
                    .accessTokenExpiresAt(clock.instant().minus(Duration.ofMinutes(5)))
                    .status(ConnectedSourceStatus.ACTIVE)
                    .scopes(originalScopes)
                    .failureCount(4).lastError("SERVER_ERROR")
                    .build());

            Instant newAccessExp = clock.instant().plus(Duration.ofHours(6)).truncatedTo(ChronoUnit.MICROS);
            Instant newRefreshExp = clock.instant().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.MICROS);
            TokensDto refreshed = new TokensDto(
                    new AccessTokenDto("at-new", newAccessExp),
                    new RefreshTokenDto("rt-new", newRefreshExp),
                    // Scopes from the refresh response are IGNORED — Strava does not re-send
                    // them, and the stored scopes column is authoritative.
                    List.of("this-should-not-overwrite"));

            TokensDto result = tokenStateManager.persistRefreshed(userId, refreshed);

            assertThat(result.accessToken().token()).isEqualTo("at-new");
            assertThat(result.accessToken().expiresAt()).isEqualTo(newAccessExp);
            assertThat(result.refreshToken().token()).isEqualTo("rt-new");
            assertThat(result.refreshToken().expiresAt()).isEqualTo(newRefreshExp);
            assertThat(result.scopes()).containsExactlyElementsOf(originalScopes);

            ConnectedSourceEntity reloaded = loadSource(userId);
            assertThat(reloaded.getAccessToken()).isEqualTo("at-new");
            assertThat(reloaded.getRefreshToken()).isEqualTo("rt-new");
            assertThat(reloaded.getAccessTokenExpiresAt()).isEqualTo(newAccessExp);
            assertThat(reloaded.getRefreshTokenExpiresAt()).isEqualTo(newRefreshExp);
            assertThat(reloaded.getLastRefreshedAt()).isNotNull();
            assertThat(reloaded.getFailureCount())
                    .as("a successful refresh must reset the failure counter")
                    .isZero();
            assertThat(reloaded.getLastError())
                    .as("a successful refresh must clear the last error marker")
                    .isNull();
            assertThat(reloaded.getScopes())
                    .as("scopes column is authoritative — refresh must not overwrite it")
                    .containsExactlyElementsOf(originalScopes);
        }

        @Test
        @DisplayName("Strava-style null refresh_token_expires_at → persisted as null, no crash")
        void nullRefreshExpiryIsPersisted() {
            UUID userId = fixtures.seedActiveSource(clock.instant().minus(Duration.ofMinutes(5)));

            TokensDto refreshed = new TokensDto(
                    new AccessTokenDto("at-new", clock.instant().plus(Duration.ofHours(6))),
                    new RefreshTokenDto("rt-new", null),
                    List.of());

            tokenStateManager.persistRefreshed(userId, refreshed);

            assertThat(loadSource(userId).getRefreshTokenExpiresAt())
                    .as("Strava's refresh_token_expires_at is nullable — must be tolerated")
                    .isNull();
        }

        @Test
        @DisplayName("REVOKED source → IntegrationNotActiveException, no fields mutated")
        void revokedRejectsPersist() {
            UUID userId = fixtures.seedRevokedSource(clock.instant().minus(Duration.ofMinutes(5)));
            ConnectedSourceEntity before = loadSource(userId);

            TokensDto refreshed = new TokensDto(
                    new AccessTokenDto("at-new", clock.instant().plus(Duration.ofHours(6))),
                    new RefreshTokenDto("rt-new", null),
                    List.of());

            assertThatThrownBy(() -> tokenStateManager.persistRefreshed(userId, refreshed))
                    .isInstanceOf(IntegrationNotActiveException.class);

            ConnectedSourceEntity after = loadSource(userId);
            assertThat(after.getAccessToken()).isEqualTo(before.getAccessToken());
            assertThat(after.getRefreshToken()).isEqualTo(before.getRefreshToken());
            assertThat(after.getStatus()).isEqualTo(ConnectedSourceStatus.REVOKED);
        }

        @Test
        @DisplayName("missing source → ConnectedSourceNotFoundException")
        void missingSourceRejectsPersist() {
            TokensDto refreshed = new TokensDto(
                    new AccessTokenDto("at", clock.instant().plus(Duration.ofHours(1))),
                    new RefreshTokenDto("rt", null),
                    List.of());

            assertThatThrownBy(() -> tokenStateManager.persistRefreshed(UUID.randomUUID(), refreshed))
                    .isInstanceOf(ConnectedSourceNotFoundException.class);
        }
    }

    // -----------------------------------------------------------------------

    private ConnectedSourceEntity loadSource(UUID userId) {
        return connectedSourceRepository
                .findByUserIdAndProvider(userId, DataProvider.STRAVA)
                .orElseThrow();
    }
}
