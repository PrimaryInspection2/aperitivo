package dev.amishko.aperitivo.identity.service.aperitivo;

import dev.amishko.aperitivo.config.BaseIT;
import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoRefreshToken;
import dev.amishko.aperitivo.identity.repository.RefreshTokenRepository;
import dev.amishko.aperitivo.identity.repository.UserRepository;
import dev.amishko.aperitivo.identity.repository.entity.RefreshTokenEntity;
import dev.amishko.aperitivo.identity.repository.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static dev.amishko.aperitivo.identity.util.cipher.TokenHashUtil.hashToken;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issuance half of ADR 0013 §2: what is handed to the client vs what is persisted.
 */
class AperitivoRefreshTokenIssuerIT extends BaseIT {

    @Autowired
    private AperitivoRefreshTokenIssuer issuer;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private Clock clock;

    private UUID userId;

    @BeforeEach
    void seedUser() {
        userId = userRepository.saveAndFlush(UserEntity.builder()
                .id(UUID.randomUUID()).displayName("refresh-user").build()).getId();
    }

    @Test
    @DisplayName("new family: 256-bit base64url raw token to the client, only its SHA-256 in the DB")
    void newFamilyStoresOnlyTheHash() {
        AperitivoRefreshToken issued = issuer.issueNewFamily(userId);

        assertThat(Base64.getUrlDecoder().decode(issued.refreshToken())).as("256 random bits").hasSize(32);
        assertThat(refreshTokenRepository.findById(issued.refreshToken()))
                .as("the raw token is never a key in the table").isEmpty();

        RefreshTokenEntity stored = refreshTokenRepository.findById(hashToken(issued.refreshToken())).orElseThrow();
        assertThat(stored.getUserId()).isEqualTo(userId);
        assertThat(stored.getFamilyId()).isNotNull();
        assertThat(stored.getUsedAt()).isNull();
        assertThat(stored.getRevokedAt()).isNull();
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getExpiresAt()).isEqualTo(issued.expiresAt())
                .isCloseTo(clock.instant().plus(Duration.ofDays(14)), within(Duration.ofMinutes(1)));
        assertThat(stored.getFamilyCreatedAt()).isCloseTo(clock.instant(), within(Duration.ofMinutes(1)));
        assertThat(issued.toString()).doesNotContain(issued.refreshToken());
    }

    @Test
    @DisplayName("hash is deterministic, so a presented token is found by one indexed lookup")
    void hashIsDeterministic() {
        assertThat(hashToken("abc")).isEqualTo(hashToken("abc")).isNotEqualTo(hashToken("abd"));
    }

    @Test
    @DisplayName("each sign-in starts its own family")
    void eachLoginIsANewFamily() {
        UUID first = familyOf(issuer.issueNewFamily(userId));
        UUID second = familyOf(issuer.issueNewFamily(userId));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("successor keeps family id and family start, gets a fresh token and idle expiry")
    void successorStaysInFamily() {
        AperitivoRefreshToken first = issuer.issueNewFamily(userId);
        RefreshTokenEntity current = refreshTokenRepository.findById(hashToken(first.refreshToken())).orElseThrow();
        // The successor may only be inserted once its predecessor is no longer active
        // (uq_refresh_tokens_single_token_use_per_family).
        tx.executeWithoutResult(s -> refreshTokenRepository.markTokenAsUsed(current.getTokenHash(), clock.instant()));

        AperitivoRefreshToken next = tx.execute(s -> issuer.issueNextInFamily(current));

        RefreshTokenEntity stored = refreshTokenRepository.findById(hashToken(next.refreshToken())).orElseThrow();
        assertThat(next.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(stored.getFamilyId()).isEqualTo(current.getFamilyId());
        assertThat(stored.getFamilyCreatedAt()).isEqualTo(current.getFamilyCreatedAt());
        assertThat(stored.getUserId()).isEqualTo(userId);
    }

    private UUID familyOf(AperitivoRefreshToken token) {
        return refreshTokenRepository.findById(hashToken(token.refreshToken())).orElseThrow().getFamilyId();
    }

    private static org.assertj.core.data.TemporalUnitOffset within(Duration d) {
        return org.assertj.core.api.Assertions.within(d.toMillis(), java.time.temporal.ChronoUnit.MILLIS);
    }
}
