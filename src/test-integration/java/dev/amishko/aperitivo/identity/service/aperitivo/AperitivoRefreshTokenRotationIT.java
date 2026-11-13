package dev.amishko.aperitivo.identity.service.aperitivo;

import dev.amishko.aperitivo.config.BaseIT;
import dev.amishko.aperitivo.identity.dto.tokens.aperitivo.AperitivoRefreshTokenResult;
import dev.amishko.aperitivo.identity.exception.oauth.AperitivoRefreshTokenException;
import dev.amishko.aperitivo.identity.repository.UserRepository;
import dev.amishko.aperitivo.identity.repository.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static dev.amishko.aperitivo.identity.util.cipher.TokenHashUtil.hashToken;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rotation and reuse detection. Timestamps are moved into the past with plain SQL,
 * so the real {@code Clock} can stay untouched.
 */
class AperitivoRefreshTokenRotationIT extends BaseIT {

    @Autowired
    private AperitivoRefreshTokenRotationService rotationService;

    @Autowired
    private AperitivoRefreshTokenIssuer issuer;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID userId;

    @BeforeEach
    void seedUser() {
        userId = userRepository.saveAndFlush(UserEntity.builder()
                .id(UUID.randomUUID()).displayName("rotation-user").build()).getId();
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("rotate → new raw token in the same family, presented one marked used, one active token left")
        void rotateIssuesSuccessor() {
            String r1 = issuer.issueNewFamily(userId).refreshToken();

            AperitivoRefreshTokenResult response = rotationService.rotate(r1);

            String r2 = response.token().refreshToken();
            assertThat(response.userId()).isEqualTo(userId);
            assertThat(r2).isNotBlank().isNotEqualTo(r1);
            assertThat(usedAt(r1)).as("presented token is consumed").isNotNull();
            assertThat(familyOf(r2)).isEqualTo(familyOf(r1));
            assertThat(activeTokensInFamily(familyOf(r1))).isEqualTo(1);
        }

        @Test
        @DisplayName("a chain of rotations keeps working: r1 → r2 → r3")
        void chain() {
            String r1 = issuer.issueNewFamily(userId).refreshToken();
            String r2 = rotationService.rotate(r1).token().refreshToken();
            String r3 = rotationService.rotate(r2).token().refreshToken();

            assertThat(familyOf(r3)).isEqualTo(familyOf(r1));
            assertThat(activeTokensInFamily(familyOf(r1))).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("rejections → AperitivoRefreshTokenException (401)")
    class Rejections {

        @Test
        @DisplayName("unknown token")
        void unknown() {
            assertThatThrownBy(() -> rotationService.rotate("never-issued"))
                    .isInstanceOf(AperitivoRefreshTokenException.class);
        }

        @Test
        @DisplayName("idle expiry passed (expires_at in the past)")
        void idleExpired() {
            String r1 = issuer.issueNewFamily(userId).refreshToken();
            setColumn(r1, "expires_at", Instant.now().minus(Duration.ofMinutes(1)));

            assertThatThrownBy(() -> rotationService.rotate(r1)).isInstanceOf(AperitivoRefreshTokenException.class);
        }

        @Test
        @DisplayName("family older than the absolute lifetime, even with a fresh idle expiry")
        void absoluteExpired() {
            String r1 = issuer.issueNewFamily(userId).refreshToken();
            setColumn(r1, "family_created_at", Instant.now().minus(Duration.ofDays(31)));

            assertThatThrownBy(() -> rotationService.rotate(r1)).isInstanceOf(AperitivoRefreshTokenException.class);
        }

        @Test
        @DisplayName("revoked token")
        void revoked() {
            String r1 = issuer.issueNewFamily(userId).refreshToken();
            setColumn(r1, "revoked_at", Instant.now());

            assertThatThrownBy(() -> rotationService.rotate(r1)).isInstanceOf(AperitivoRefreshTokenException.class);
        }
    }

    @Nested
    @DisplayName("reuse detection")
    class Reuse {

        @Test
        @DisplayName("used token presented after the grace window → 401 AND the whole family stays revoked (committed)")
        void reuseRevokesFamily() {
            String r1 = issuer.issueNewFamily(userId).refreshToken();
            String r2 = rotationService.rotate(r1).token().refreshToken();
            setColumn(r1, "used_at", Instant.now().minus(Duration.ofMinutes(5)));

            assertThatThrownBy(() -> rotationService.rotate(r1)).isInstanceOf(AperitivoRefreshTokenException.class);

            assertThat(revokedAt(r2))
                    .as("revocation must survive the exception: the tx must not roll it back")
                    .isNotNull();
            assertThatThrownBy(() -> rotationService.rotate(r2))
                    .as("the thief's successor is dead too")
                    .isInstanceOf(AperitivoRefreshTokenException.class);
        }

        @Test
        @DisplayName("used token presented within the grace window → a new successor, still one active token")
        void graceWindow() {
            String r1 = issuer.issueNewFamily(userId).refreshToken();
            String r2 = rotationService.rotate(r1).token().refreshToken();
            setColumn(r1, "used_at", Instant.now().minus(Duration.ofSeconds(2)));

            String r2b = rotationService.rotate(r1).token().refreshToken();

            assertThat(r2b).isNotEqualTo(r2);
            assertThat(familyOf(r2b)).isEqualTo(familyOf(r1));
            assertThat(activeTokensInFamily(familyOf(r1)))
                    .as("partial unique: exactly one active token per family")
                    .isEqualTo(1);
            assertThat(revokedAt(r2b)).isNull();
        }

        @Test
        @DisplayName("two tabs refresh the same token at the same instant → both succeed, family not split")
        void concurrentRefresh() throws Exception {
            String r1 = issuer.issueNewFamily(userId).refreshToken();
            CyclicBarrier start = new CyclicBarrier(2);
            Callable<String> refresh = () -> {
                start.await(10, SECONDS);
                return rotationService.rotate(r1).token().refreshToken();
            };

            List<String> results;
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                Future<String> a = pool.submit(refresh);
                Future<String> b = pool.submit(refresh);
                results = List.of(a.get(30, SECONDS), b.get(30, SECONDS));
            }

            assertThat(results).doesNotHaveDuplicates();
            assertThat(activeTokensInFamily(familyOf(r1))).isEqualTo(1);
        }
    }

    private void setColumn(String rawToken, String column, Instant value) {
        jdbc.update("UPDATE identity.refresh_tokens SET " + column + " = ? WHERE token_hash = ?",
                Timestamp.from(value), hashToken(rawToken));
    }

    private Timestamp usedAt(String rawToken) {
        return column(rawToken, "used_at");
    }

    private Timestamp revokedAt(String rawToken) {
        return column(rawToken, "revoked_at");
    }

    private Timestamp column(String rawToken, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM identity.refresh_tokens WHERE token_hash = ?",
                Timestamp.class, hashToken(rawToken));
    }

    private UUID familyOf(String rawToken) {
        return jdbc.queryForObject("SELECT family_id FROM identity.refresh_tokens WHERE token_hash = ?",
                UUID.class, hashToken(rawToken));
    }

    private int activeTokensInFamily(UUID familyId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM identity.refresh_tokens
                WHERE family_id = ? AND used_at IS NULL AND revoked_at IS NULL
                """, Integer.class, familyId);
    }
}
