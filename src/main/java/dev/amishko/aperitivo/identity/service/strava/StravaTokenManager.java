package dev.amishko.aperitivo.identity.service.strava;

import dev.amishko.aperitivo.identity.TokenManager;
import dev.amishko.aperitivo.identity.dto.DataProvider;
import dev.amishko.aperitivo.identity.dto.RevocationReason;
import dev.amishko.aperitivo.identity.dto.TokenUseFailureKind;
import dev.amishko.aperitivo.identity.dto.tokens.AccessTokenDto;
import dev.amishko.aperitivo.identity.dto.tokens.TokensDto;
import dev.amishko.aperitivo.identity.events.dto.IntegrationRevokedEvent;
import dev.amishko.aperitivo.identity.exception.terminal.ConnectedSourceNotFoundException;
import dev.amishko.aperitivo.identity.feign.strava.StravaOAuthClient;
import dev.amishko.aperitivo.identity.repository.ConnectedSourceRepository;
import dev.amishko.aperitivo.identity.repository.entity.ConnectedSourceEntity;
import dev.amishko.aperitivo.identity.service.TokenStateManager;
import dev.amishko.aperitivo.identity.util.lock.PerUserTokenRefreshLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;

import static dev.amishko.aperitivo.identity.dto.ConnectedSourceStatus.REVOKED;

/**
 * Contract for upstream
 * <p>
 * 200 OK                              → recordSuccessfulUse(userId)
 * 401 Unauthorized (data call)        → markRevoked(userId, TOKEN_REJECTED)
 * OAuthBadRequestException            → markRevoked(userId, TOKEN_REJECTED)   [refresh invalid_grant — dead token]
 * OAuthClientCredentialsException     → NOT markRevoked. Job → system_paused, re-queued.
 * Circuit breaker при N-th разі відкривається (C.10) → alert.
 * Не робимо @Retryable — credentials константні, retry не поможе.
 * OAuthTransientException             → recordFailure(userId, ...); retry job later
 * LockAcquiringException              → retry job later (self-inflicted, не викликаємо recordFailure —
 * це не збій інтеграції, а наш власний lock timeout)
 * IntegrationNotActiveException       → source уже REVOKED; caller marks SyncJob як cancelled + логує;
 * не викликає жодного write-методу
 * 429 / 5xx / net (на data call)      → recordFailure(userId, kind); retry
 *
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StravaTokenManager implements TokenManager {

    private final TokenStateManager stravaTokenStateService;
    private final PerUserTokenRefreshLock perUserTokenRefreshLock;
    private final StravaOAuthClient stravaOAuthClient;
    private final ConnectedSourceRepository connectedSourceRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Value("${aperitivo.identity.token-refresh.expiration-buffer}")
    private Duration expirationBuffer;

    @Override
    public AccessTokenDto getValidAccessToken(UUID userId) {
        TokensDto existingTokens = stravaTokenStateService.readSnapshot(userId);
        if (isStillValid(existingTokens)) {
            return existingTokens.accessToken();
        }
        //Rare case when token is expired, and we need lock per (user,provider) when 2 webhooks will try to refresh the same token
        return perUserTokenRefreshLock.executeWithLock(DataProvider.STRAVA, userId, () -> {
            TokensDto reReadExistingToken = stravaTokenStateService.readSnapshot(userId);
            if (isStillValid(reReadExistingToken)) {
                return reReadExistingToken.accessToken();
            }
            TokensDto stravaResponse = stravaOAuthClient.refreshAccessToken(reReadExistingToken.refreshToken().token());

            TokensDto updatedTokens = stravaTokenStateService.persistRefreshed(userId, stravaResponse);
            return updatedTokens.accessToken();
        });
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttemptsExpression = "${aperitivo.identity.token-refresh.retry.max-attempts}",
            backoff = @Backoff(
                    delayExpression = "${aperitivo.identity.token-refresh.retry.initial-delay-ms}",
                    multiplierExpression = "${aperitivo.identity.token-refresh.retry.multiplier}"))
    @Transactional
    @Override
    public void markRevoked(UUID userId, RevocationReason reason) {
        ConnectedSourceEntity connectedSourceEntity = connectedSourceRepository.findByUserIdAndProvider(userId, DataProvider.STRAVA)
                .orElseThrow(() -> new ConnectedSourceNotFoundException("No connected source found for user " + userId + " and provider " + DataProvider.STRAVA));
        if (REVOKED.equals(connectedSourceEntity.getStatus())) {
            log.debug("markRevoked skipped — already REVOKED (user={}, reason={})", userId, reason);
            return;
        }
        connectedSourceEntity.setStatus(REVOKED);
        connectedSourceEntity.setScopes(new ArrayList<>());
        eventPublisher.publishEvent(new IntegrationRevokedEvent(userId, connectedSourceEntity.getId(), DataProvider.STRAVA, reason, clock.instant()));
    }

    @Async("iamAuditTaskExecutor")
    @Transactional
    @Override
    public void recordSuccessfulUse(UUID userId) {
        try {
            ConnectedSourceEntity connectedSourceEntity = connectedSourceRepository.findByUserIdAndProvider(userId, DataProvider.STRAVA)
                    .orElseThrow(() -> new ConnectedSourceNotFoundException("No connected source found for user " + userId + " and provider " + DataProvider.STRAVA));
            connectedSourceEntity.setLastUsedAt(clock.instant());
            connectedSourceEntity.setLastError(null);
            connectedSourceEntity.setFailureCount(0);
        } catch (Exception e) {
            log.error("Error recording successful use for user {}: {}", userId, e.getMessage(), e);
        }

    }

    @Async("iamAuditTaskExecutor")
    @Transactional
    @Override
    public void recordFailure(UUID userId, TokenUseFailureKind kind) {
        try {
            ConnectedSourceEntity connectedSourceEntity = connectedSourceRepository.findByUserIdAndProvider(userId, DataProvider.STRAVA)
                    .orElseThrow(() -> new ConnectedSourceNotFoundException("No connected source found for user " + userId + " and provider " + DataProvider.STRAVA));
            connectedSourceEntity.setLastUsedAt(clock.instant());
            connectedSourceEntity.setLastError(kind.name());
            connectedSourceEntity.setFailureCount(connectedSourceEntity.getFailureCount() + 1);

            //todo escalation will be implemented in ingestion or other "caller-modules" via TokenManager
        } catch (Exception e) {
            log.error("Error recording failure for user {}: {}", userId, e.getMessage(), e);
        }

    }

    private boolean isStillValid(TokensDto tokens) {
        return clock.instant().plus(expirationBuffer).isBefore(tokens.accessToken().expiresAt());
    }

    //todo consider adding recover here
}
