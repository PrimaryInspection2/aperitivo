package dev.amishko.aperitivo.identity.service.aperitivo;

import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoRefreshToken;
import dev.amishko.aperitivo.identity.repository.RefreshTokenRepository;
import dev.amishko.aperitivo.identity.repository.entity.RefreshTokenEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import static dev.amishko.aperitivo.identity.util.cipher.TokenHashUtil.hashToken;
import static org.apache.commons.lang3.compare.ComparableUtils.min;


@Service
@RequiredArgsConstructor
public class AperitivoRefreshTokenIssuer {

    private static final StringKeyGenerator RAW_TOKEN_GENERATOR =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32);

    private final RefreshTokenRepository refreshTokenRepository;
    private final AperitivoRefreshTokenProperties aperitivoRefreshTokenProperties;
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
    @Transactional
    public AperitivoRefreshToken issueNextInFamily(RefreshTokenEntity current) {
        return issue(current.getUserId(), current.getFamilyId(), current.getFamilyCreatedAt(), now());
    }

    private AperitivoRefreshToken issue(UUID userId, UUID familyId, Instant familyCreatedAt, Instant now) {
        String rawToken = RAW_TOKEN_GENERATOR.generateKey();

        Instant expirationWithoutRotation = now.plus(aperitivoRefreshTokenProperties.inactiveDaysForInvalidation(), ChronoUnit.DAYS);
        Instant absoluteExpiration = familyCreatedAt.plus(aperitivoRefreshTokenProperties.absoluteDaysForInvalidation(), ChronoUnit.DAYS);
        Instant expiresAt = min(expirationWithoutRotation, absoluteExpiration);

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
