package dev.amishko.aperitivo.identity.service;

import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoRefreshToken;
import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoRefreshTokenResponse;
import dev.amishko.aperitivo.identity.repository.RefreshTokenRepository;
import dev.amishko.aperitivo.identity.repository.entity.RefreshTokenEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

import static java.util.Objects.isNull;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final StringKeyGenerator REFRESH_TOKEN_GENERATOR = new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32);

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    @Transactional
    public AperitivoRefreshTokenResponse rotate(AperitivoRefreshToken aperitivoRefreshToken) {
        String currentRawToken = aperitivoRefreshToken.refreshToken();
        String currentTokenHash = generateTokenHash(currentRawToken);
        RefreshTokenEntity refreshToken = refreshTokenRepository.findById(currentTokenHash)
                .orElseThrow(() -> new RuntimeException("Refresh token not found"));

        UUID userId = refreshToken.getUserId();
        UUID familyId = refreshToken.getFamilyId();
        Instant now = Instant.now(clock);

        validateExpiredOrRevoked(refreshToken, now, userId, familyId);

        int updatedResult = refreshTokenRepository.markTokenAsUsed(currentTokenHash);

        //Support two and more rotation requests initiated at almost the same time.

        if (updatedResult == 0 && !isNull(refreshToken.getUsedAt())) {
            return processGraceWindow(refreshToken, currentTokenHash, now, userId, familyId);
        }
        return issueNewRefreshToken(userId, familyId);
    }

    public RefreshTokenEntity createNextTokenInFamily(UUID userId, UUID familyId, String tokenHash) {

        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(14, ChronoUnit.DAYS);

        familyId = Objects.isNull(familyId) ? UUID.randomUUID() : familyId; // if null -> new family, otherwise - add to existing family

        return buildRefreshToken(tokenHash, userId, familyId, now, expiresAt);
    }

    private void validateExpiredOrRevoked(RefreshTokenEntity refreshToken, Instant now, UUID userId, UUID familyId) {
        if (!Objects.isNull(refreshToken.getRevokedAt())) {
            throw new RuntimeException("Token has revoked");
        }

        if (refreshToken.getExpiresAt().isBefore(now)) {
            throw new RuntimeException("Token is expired");
        }

        Instant oldestTokenInFamilyDate = refreshTokenRepository.findOldestTokenInFamily(userId, familyId);
        if (oldestTokenInFamilyDate.plus(30, ChronoUnit.DAYS).isBefore(now)) {
            throw new RuntimeException("Token is expired");
        }
    }

    private AperitivoRefreshTokenResponse processGraceWindow(RefreshTokenEntity refreshToken, String currentTokenHash, Instant now, UUID userId, UUID familyId) {
        return refreshTokenRepository.findById(currentTokenHash)
                .filter(token -> token.getUsedAt().plus(5, ChronoUnit.SECONDS).isBefore(now))
                .map(token -> {
                    refreshTokenRepository.markTokenAsUsed(currentTokenHash);
                    return issueNewRefreshToken(userId, familyId);
                })
                .orElseThrow(() -> {
                    refreshTokenRepository.revokeTokenFamily(userId, familyId);
                    return new RuntimeException("Refresh token has expired");
                });
    }

    private AperitivoRefreshTokenResponse issueNewRefreshToken(UUID userId, UUID familyId) {
        String nextRawRefreshToken = generateRawTokenValue();
        String nextTokenHash = generateTokenHash(nextRawRefreshToken);
        RefreshTokenEntity nextTokenInFamily = createNextTokenInFamily(userId, familyId, nextTokenHash);

        AperitivoRefreshToken aperitivoRefreshTokenResponse = new AperitivoRefreshToken(nextTokenInFamily.getTokenHash(), nextTokenInFamily.getExpiresAt());
        return new AperitivoRefreshTokenResponse(aperitivoRefreshTokenResponse, userId);
    }

    private RefreshTokenEntity buildRefreshToken(String tokenHash, UUID userId, UUID familyId, Instant createdAt, Instant expiresAt) {
        return RefreshTokenEntity.builder()
                .tokenHash(tokenHash)
                .userId(userId)
                .familyId(familyId)
                .createdAt(createdAt)
                .expiresAt(expiresAt)
                .usedAt(null)
                .revokedAt(null)
                .build();
    }

    private String generateRawTokenValue() {
        return REFRESH_TOKEN_GENERATOR.generateKey();
    }

    private String generateTokenHash(String rawToken) {
        byte[] bytes = rawToken.getBytes(StandardCharsets.UTF_8);
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }
}
