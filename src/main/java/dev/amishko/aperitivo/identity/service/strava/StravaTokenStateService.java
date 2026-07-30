package dev.amishko.aperitivo.identity.service.strava;

import dev.amishko.aperitivo.identity.dto.DataProvider;
import dev.amishko.aperitivo.identity.dto.tokens.AccessTokenDto;
import dev.amishko.aperitivo.identity.dto.tokens.RefreshTokenDto;
import dev.amishko.aperitivo.identity.dto.tokens.TokensDto;
import dev.amishko.aperitivo.identity.exception.terminal.ConnectedSourceNotFoundException;
import dev.amishko.aperitivo.identity.exception.terminal.IntegrationNotActiveException;
import dev.amishko.aperitivo.identity.repository.ConnectedSourceRepository;
import dev.amishko.aperitivo.identity.repository.entity.ConnectedSourceEntity;
import dev.amishko.aperitivo.identity.service.TokenStateManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

import static dev.amishko.aperitivo.identity.dto.ConnectedSourceStatus.ACTIVE;
import static dev.amishko.aperitivo.identity.dto.ConnectedSourceStatus.REVOKED;

@Service
@RequiredArgsConstructor
@Slf4j
public class StravaTokenStateService implements TokenStateManager {

    private final ConnectedSourceRepository connectedSourceRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public TokensDto readSnapshot(UUID userId) {
        ConnectedSourceEntity connectedSourceEntity = connectedSourceRepository.findByUserIdAndProvider(userId, DataProvider.STRAVA)
                .orElseThrow(() -> new ConnectedSourceNotFoundException("No connected source found for user " + userId + " and provider STRAVA"));

        if (!ACTIVE.equals(connectedSourceEntity.getStatus())) {
            throw new IntegrationNotActiveException("Access token is not active for user " + connectedSourceEntity.getUserId());
        }
        return new TokensDto(new AccessTokenDto(connectedSourceEntity.getAccessToken(), connectedSourceEntity.getAccessTokenExpiresAt()),
                new RefreshTokenDto(connectedSourceEntity.getRefreshToken(), connectedSourceEntity.getRefreshTokenExpiresAt()),
                connectedSourceEntity.getScopes());
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttemptsExpression = "${aperitivo.identity.token-refresh.retry.max-attempts}",
            backoff = @Backoff(
                    delayExpression = "${aperitivo.identity.token-refresh.retry.initial-delay-ms}",
                    multiplierExpression = "${aperitivo.identity.token-refresh.retry.multiplier}"))
    @Override
    @Transactional
    public TokensDto persistRefreshed(UUID userId, TokensDto refreshedTokens) {
        ConnectedSourceEntity connectedSourceEntity = connectedSourceRepository.findByUserIdAndProvider(userId, DataProvider.STRAVA)
                .orElseThrow(() -> new ConnectedSourceNotFoundException("No connected source found for user " + userId + " and provider STRAVA"));

        if (REVOKED.equals(connectedSourceEntity.getStatus())) {
            throw new IntegrationNotActiveException("Access token is revoked for user " + connectedSourceEntity.getUserId());
        }

        connectedSourceEntity.setAccessToken(refreshedTokens.accessToken().token());
        connectedSourceEntity.setRefreshToken(refreshedTokens.refreshToken().token());
        connectedSourceEntity.setAccessTokenExpiresAt(refreshedTokens.accessToken().expiresAt());
        connectedSourceEntity.setRefreshTokenExpiresAt(refreshedTokens.refreshToken().expiresAt()); //may be null, double check it (strava)
        connectedSourceEntity.setLastRefreshedAt(clock.instant());
        connectedSourceEntity.setLastError(null);
        connectedSourceEntity.setFailureCount(0);

        return new TokensDto(new AccessTokenDto(connectedSourceEntity.getAccessToken(), connectedSourceEntity.getAccessTokenExpiresAt()),
                new RefreshTokenDto(connectedSourceEntity.getRefreshToken(), connectedSourceEntity.getRefreshTokenExpiresAt()),
                connectedSourceEntity.getScopes());
    }
}
