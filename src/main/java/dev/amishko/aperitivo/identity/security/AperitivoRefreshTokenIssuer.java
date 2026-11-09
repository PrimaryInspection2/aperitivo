package dev.amishko.aperitivo.identity.security;

import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoRefreshToken;
import dev.amishko.aperitivo.identity.repository.RefreshTokenRepository;
import dev.amishko.aperitivo.identity.repository.entity.RefreshTokenEntity;
import lombok.RequiredArgsConstructor;
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
import java.util.UUID;


@Service
@RequiredArgsConstructor
public class AperitivoRefreshTokenIssuer {

    private static final StringKeyGenerator RAW_TOKEN_GENERATOR =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32);

    private final RefreshTokenRepository refreshTokenRepository;
    private final AperitivoRefreshTokenProperties properties;
    private final Clock clock;

    /**
     * Starts a new family: one sign-in on one device.
     */
    @Transactional
    public AperitivoRefreshToken issueNewFamily(UUID userId) {
        Instant now = now();
        return issue(userId, UUID.randomUUID(), now, now);
    }

    /**
     * Successor of {@code current} in the same family; the family's absolute start is carried over.
     */
    protected AperitivoRefreshToken issueNextInFamily(RefreshTokenEntity current) {
        return issue(current.getUserId(), current.getFamilyId(), current.getFamilyCreatedAt(), now());
    }

    /**
     * Deterministic SHA-256 (base64): the same raw token always maps to the same stored hash.
     */
    protected String hashToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }

    private AperitivoRefreshToken issue(UUID userId, UUID familyId, Instant familyCreatedAt, Instant now) {
        String rawToken = RAW_TOKEN_GENERATOR.generateKey();
        Instant expiresAt = now.plus(properties.inactiveDaysForInvalidation(), ChronoUnit.DAYS);

        refreshTokenRepository.save(RefreshTokenEntity.builder()
                .tokenHash(hashToken(rawToken))
                .userId(userId)
                .familyId(familyId)
                .familyCreatedAt(familyCreatedAt)
                .expiresAt(expiresAt)
                .build());

        return new AperitivoRefreshToken(rawToken, expiresAt);
    }

    // TIMESTAMPTZ keeps microseconds: truncate so the expiry handed to the client equals the stored one.
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

}
