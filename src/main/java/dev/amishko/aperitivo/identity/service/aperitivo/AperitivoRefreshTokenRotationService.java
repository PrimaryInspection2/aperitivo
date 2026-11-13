package dev.amishko.aperitivo.identity.service.aperitivo;

import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoRefreshTokenResult;
import dev.amishko.aperitivo.identity.exception.oauth.AperitivoRefreshTokenException;
import dev.amishko.aperitivo.identity.repository.RefreshTokenRepository;
import dev.amishko.aperitivo.identity.repository.entity.RefreshTokenEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import static dev.amishko.aperitivo.identity.util.cipher.TokenHashUtil.hashToken;

@Service
@RequiredArgsConstructor
public class AperitivoRefreshTokenRotationService {

    private final AperitivoRefreshTokenIssuer refreshTokenIssuer;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AperitivoRefreshTokenProperties aperitivoRefreshTokenProperties;
    private final Clock clock;

    @Transactional(noRollbackFor = AperitivoRefreshTokenException.class)
    public AperitivoRefreshTokenResult rotate(String currentToken) {
        Instant now = Instant.now(clock);
        String currentTokenHash = hashToken(currentToken);
        RefreshTokenEntity refreshToken = refreshTokenRepository.findByTokenHashForUpdate(currentTokenHash)
                .orElseThrow(() -> new AperitivoRefreshTokenException("Refresh token not found"));

        validateExpiredOrRevoked(refreshToken, now);

        if (refreshToken.getUsedAt() == null) {
            refreshTokenRepository.markTokenAsUsed(currentTokenHash, now);
            return issueNext(refreshToken);
        }
        if (isTokenUsedWithinGraceWindow(refreshToken, now)) {
            refreshTokenRepository.revokeActiveTokenInFamily(refreshToken.getUserId(), refreshToken.getFamilyId(), now);
            return issueNext(refreshToken);
        }

        refreshTokenRepository.revokeTokenFamily(refreshToken.getUserId(), refreshToken.getFamilyId(), now);
        throw new AperitivoRefreshTokenException("Refresh Token reuse detected");
    }

    private AperitivoRefreshTokenResult issueNext(RefreshTokenEntity current) {
        return new AperitivoRefreshTokenResult(refreshTokenIssuer.issueNextInFamily(current), current.getUserId());
    }

    private void validateExpiredOrRevoked(RefreshTokenEntity refreshToken, Instant now) {
        if (!Objects.isNull(refreshToken.getRevokedAt())) {
            throw new AperitivoRefreshTokenException("Token has revoked");
        }

        if (refreshToken.getExpiresAt().isBefore(now)) {
            throw new AperitivoRefreshTokenException("Token is expired");
        }

        if (refreshToken.getFamilyCreatedAt().plus(aperitivoRefreshTokenProperties.absoluteDaysForInvalidation(), ChronoUnit.DAYS).isBefore(now)) {
            throw new AperitivoRefreshTokenException("Token is expired");
        }
    }

    private boolean isTokenUsedWithinGraceWindow(RefreshTokenEntity currentToken, Instant now) {
        return currentToken.getUsedAt()
                .plusSeconds(aperitivoRefreshTokenProperties.graceWindowSeconds())
                .isAfter(now);
    }
}
