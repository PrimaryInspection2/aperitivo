package dev.amishko.aperitivo.identity.security;

import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoRefreshToken;
import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoRefreshTokenResponse;
import dev.amishko.aperitivo.identity.repository.RefreshTokenRepository;
import dev.amishko.aperitivo.identity.repository.entity.RefreshTokenEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import static java.util.Objects.isNull;

@Service
@RequiredArgsConstructor
public class AperitivoRefreshTokenRotationService {

    private final AperitivoRefreshTokenIssuer refreshTokenIssuer;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;
    private final AperitivoRefreshTokenProperties aperitivoRefreshTokenProperties;

    @Transactional
    public AperitivoRefreshTokenResponse rotate(String currentToken) {
        String currentTokenHash = refreshTokenIssuer.hashToken(currentToken);
        RefreshTokenEntity refreshToken = refreshTokenRepository.findById(currentTokenHash)
                .orElseThrow(() -> new RuntimeException("Refresh token not found"));

        UUID userId = refreshToken.getUserId();
        UUID familyId = refreshToken.getFamilyId();
        Instant now = Instant.now(clock);

        validateExpiredOrRevoked(refreshToken, now);

        int updatedResult = refreshTokenRepository.markTokenAsUsed(currentTokenHash, now);

        //todo need to re-read entity after CAS properly
        //Support two and more rotation requests initiated at almost the same time.
        // result == 0 -> means that someone already marked it as used and we give it a try in scope of grace window.
        if (updatedResult == 0 && !isNull(refreshToken.getUsedAt())) {
            return processGraceWindow(currentTokenHash, now, userId, familyId);
        }
        return issueNext(refreshToken);
    }

    private AperitivoRefreshTokenResponse processGraceWindow(String currentTokenHash, Instant now, UUID userId, UUID familyId) {
        return refreshTokenRepository.findById(currentTokenHash)
                .filter(token -> token.getUsedAt().plusSeconds(aperitivoRefreshTokenProperties.graceWindowSeconds()).isAfter(now))
                .map(token -> {
                    refreshTokenRepository.markTokenAsUsed(currentTokenHash, now);
                    return issueNext(token);
                })
                .orElseGet(() -> {
                    refreshTokenRepository.revokeTokenFamily(userId, familyId, now);
                    return new AperitivoRefreshTokenResponse(new AperitivoRefreshToken(null, null), userId);
                });
    }

    private AperitivoRefreshTokenResponse issueNext(RefreshTokenEntity current) {
        return new AperitivoRefreshTokenResponse(refreshTokenIssuer.issueNextInFamily(current), current.getUserId());
    }

    private void validateExpiredOrRevoked(RefreshTokenEntity refreshToken, Instant now) {
        if (!Objects.isNull(refreshToken.getRevokedAt())) {
            throw new BadCredentialsException("Token has revoked");
        }

        if (refreshToken.getExpiresAt().isBefore(now)) {
            throw new BadCredentialsException("Token is expired");
        }

        if (refreshToken.getFamilyCreatedAt().plus(aperitivoRefreshTokenProperties.absoluteDaysForInvalidation(), ChronoUnit.DAYS).isBefore(now)) {
            throw new BadCredentialsException("Token is expired");
        }
    }
}
