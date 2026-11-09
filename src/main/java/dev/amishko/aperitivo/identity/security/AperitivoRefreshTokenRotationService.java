package dev.amishko.aperitivo.identity.security;

import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoRefreshToken;
import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoRefreshTokenResponse;
import dev.amishko.aperitivo.identity.repository.RefreshTokenRepository;
import dev.amishko.aperitivo.identity.repository.entity.RefreshTokenEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

import static java.util.Objects.isNull;

@Service
@RequiredArgsConstructor
public class AperitivoRefreshTokenRotationService {

    private static final StringKeyGenerator REFRESH_TOKEN_GENERATOR = new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32);

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;
    private final AperitivoRefreshTokenProperties  aperitivoRefreshTokenProperties;

    @Transactional
    public AperitivoRefreshTokenResponse rotate(String currentToken) {
        String currentTokenHash = generateTokenHash(currentToken);
        RefreshTokenEntity refreshToken = refreshTokenRepository.findById(currentTokenHash)
                .orElseThrow(() -> new RuntimeException("Refresh token not found"));

        UUID userId = refreshToken.getUserId();
        UUID familyId = refreshToken.getFamilyId();
        Instant now = Instant.now(clock);

        validateExpiredOrRevoked(refreshToken, now, userId, familyId);

        int updatedResult = refreshTokenRepository.markTokenAsUsed(currentTokenHash, now);

        //todo need to re-read entity after CAS properly
        //Support two and more rotation requests initiated at almost the same time.
        // result == 0 -> means that someone already marked it as used and we give it a try in scope of grace window.
        if (updatedResult == 0 && !isNull(refreshToken.getUsedAt())) {
            return processGraceWindow(currentTokenHash, now, userId, familyId);
        }
        return issueNewRefreshToken(userId, familyId);
    }

    @Transactional
    public RefreshTokenEntity issueNewFamily(UUID userId, String tokenHash) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(aperitivoRefreshTokenProperties.inactiveDaysForInvalidation(), ChronoUnit.DAYS);
        RefreshTokenEntity refreshTokenEntity = buildRefreshToken(tokenHash, userId, null, now, expiresAt);
        refreshTokenRepository.save(refreshTokenEntity);
        return refreshTokenEntity;
    }

    private AperitivoRefreshTokenResponse processGraceWindow(String currentTokenHash, Instant now, UUID userId, UUID familyId) {
        return refreshTokenRepository.findById(currentTokenHash)
                .filter(token -> token.getUsedAt().plusSeconds(aperitivoRefreshTokenProperties.graceWindowSeconds()).isAfter(now))
                .map(token -> {
                    refreshTokenRepository.markTokenAsUsed(currentTokenHash, now);
                    return issueNewRefreshToken(userId, familyId);
                })
                .orElseGet(() -> {
                    refreshTokenRepository.revokeTokenFamily(userId, familyId, now);
                    return new AperitivoRefreshTokenResponse(new AperitivoRefreshToken(null, null), userId);
                });
    }


    private RefreshTokenEntity createNextTokenInFamily(UUID userId, UUID familyId, String tokenHash) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(aperitivoRefreshTokenProperties.inactiveDaysForInvalidation(), ChronoUnit.DAYS);
        RefreshTokenEntity refreshTokenEntity = buildRefreshToken(tokenHash, userId, familyId, now, expiresAt);
        refreshTokenRepository.save(refreshTokenEntity);
        return refreshTokenEntity;
    }

    private AperitivoRefreshTokenResponse issueNewRefreshToken(UUID userId, UUID familyId) {
        String nextRawRefreshToken = generateRawTokenValue();
        String nextTokenHash = generateTokenHash(nextRawRefreshToken);
        RefreshTokenEntity nextTokenInFamily = createNextTokenInFamily(userId, familyId, nextTokenHash);

        AperitivoRefreshToken aperitivoRefreshTokenResponse = new AperitivoRefreshToken(nextRawRefreshToken, nextTokenInFamily.getExpiresAt());
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
        byte[] encodedToken;
        try {
            encodedToken = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        return Base64.getEncoder().withoutPadding().encodeToString(encodedToken);
    }

    private void validateExpiredOrRevoked(RefreshTokenEntity refreshToken, Instant now, UUID userId, UUID familyId) {
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
