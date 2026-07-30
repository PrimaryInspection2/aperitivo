package dev.amishko.aperitivo.identity.util.cipher;

import dev.amishko.aperitivo.config.BaseIT;
import dev.amishko.aperitivo.config.TestFixtures.SeedSpec;
import dev.amishko.aperitivo.identity.dto.ConnectedSourceStatus;
import dev.amishko.aperitivo.identity.dto.DataProvider;
import dev.amishko.aperitivo.identity.repository.ConnectedSourceRepository;
import dev.amishko.aperitivo.identity.repository.entity.ConnectedSourceEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of {@link EncryptedBytesConverter}: JPA + Hibernate
 * + Testcontainers-managed PostgreSQL, with raw JDBC readback to prove the
 * ciphertext hits disk as {@code bytea}.
 *
 * <p>Each seed goes through its own committing transaction (via
 * {@code TestFixtures}), so the subsequent JDBC query reads what production
 * code sees — never a within-transaction illusion.
 */
class EncryptedBytesConverterIT extends BaseIT {

    @Autowired
    private ConnectedSourceRepository connectedSourceRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("plaintext survives JPA write → JDBC read round-trip via bytea column")
    void encryptsOnWriteAndDecryptsOnRead() {
        String accessPlaintext = "access-token-strava-abc123";
        String refreshPlaintext = "refresh-token-strava-xyz789";

        UUID userId = fixtures.seedSource(SeedSpec.builder()
                .accessToken(accessPlaintext).refreshToken(refreshPlaintext)
                .accessTokenExpiresAt(clock.instant().plus(Duration.ofHours(1)))
                .status(ConnectedSourceStatus.ACTIVE)
                .scopes(List.of("read", "activity:read"))
                .build());

        ConnectedSourceEntity reloaded = loadSource(userId);
        assertThat(reloaded.getAccessToken()).isEqualTo(accessPlaintext);
        assertThat(reloaded.getRefreshToken()).isEqualTo(refreshPlaintext);
    }

    @Test
    @DisplayName("raw bytea column stores ciphertext, not plaintext, with the 12+N+16 GCM layout")
    void storesCiphertextInDatabaseNotPlaintext() {
        String plaintext = "strava-token-plain-value-QQQ";
        UUID userId = fixtures.seedSource(SeedSpec.builder()
                .accessToken(plaintext).refreshToken("some-refresh")
                .accessTokenExpiresAt(clock.instant().plus(Duration.ofHours(1)))
                .status(ConnectedSourceStatus.ACTIVE)
                .build());
        UUID sourceId = loadSource(userId).getId();

        byte[] rawColumn = jdbc.queryForObject(
                "SELECT access_token FROM identity.connected_sources WHERE id = ?",
                byte[].class, sourceId);

        assertThat(rawColumn).isNotNull();
        assertThat(new String(rawColumn, StandardCharsets.UTF_8))
                .as("plaintext must never appear in the stored column")
                .doesNotContain(plaintext);
        assertThat(rawColumn)
                .as("AES-GCM layout is 12B nonce + N ciphertext + 16B tag")
                .hasSize(12 + plaintext.getBytes(StandardCharsets.UTF_8).length + 16);
    }

    @Test
    @DisplayName("two saves of the same plaintext produce different ciphertext (nonce is fresh per encrypt)")
    void twoSavesOfSamePlaintextProduceDifferentCiphertext() {
        String samePlaintext = "identical-token";

        UUID userA = fixtures.seedSource(SeedSpec.builder()
                .displayName("u1")
                .accessToken(samePlaintext).refreshToken("r1")
                .accessTokenExpiresAt(clock.instant().plus(Duration.ofHours(1)))
                .status(ConnectedSourceStatus.ACTIVE)
                .build());
        UUID userB = fixtures.seedSource(SeedSpec.builder()
                .displayName("u2")
                .accessToken(samePlaintext).refreshToken("r2")
                .accessTokenExpiresAt(clock.instant().plus(Duration.ofHours(1)))
                .status(ConnectedSourceStatus.ACTIVE)
                .build());

        byte[] rawA = jdbc.queryForObject(
                "SELECT access_token FROM identity.connected_sources WHERE id = ?",
                byte[].class, loadSource(userA).getId());
        byte[] rawB = jdbc.queryForObject(
                "SELECT access_token FROM identity.connected_sources WHERE id = ?",
                byte[].class, loadSource(userB).getId());

        assertThat(rawA).isNotEqualTo(rawB);
    }

    // -----------------------------------------------------------------------

    private ConnectedSourceEntity loadSource(UUID userId) {
        return connectedSourceRepository
                .findByUserIdAndProvider(userId, DataProvider.STRAVA)
                .orElseThrow();
    }
}
