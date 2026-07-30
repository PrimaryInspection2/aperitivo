package dev.amishko.aperitivo.config;

import dev.amishko.aperitivo.identity.dto.ConnectedSourceStatus;
import dev.amishko.aperitivo.identity.dto.DataProvider;
import dev.amishko.aperitivo.identity.repository.ConnectedSourceRepository;
import dev.amishko.aperitivo.identity.repository.UserRepository;
import dev.amishko.aperitivo.identity.repository.entity.ConnectedSourceEntity;
import dev.amishko.aperitivo.identity.repository.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@TestComponent
@RequiredArgsConstructor
public class TestFixtures {

    private final UserRepository userRepository;
    private final ConnectedSourceRepository connectedSourceRepository;
    private final TransactionTemplate tx;
    private final Clock clock;

    public UUID seedActiveSource(Instant accessTokenExpiresAt) {
        return seedSource(SeedSpec.builder()
                .status(ConnectedSourceStatus.ACTIVE)
                .accessToken("at-old")
                .refreshToken("rt-old")
                .accessTokenExpiresAt(accessTokenExpiresAt)
                .build());
    }

    public UUID seedActiveSource() {
        return seedActiveSource(clock.instant().plus(Duration.ofHours(1)));
    }


    public UUID seedRevokedSource(Instant accessTokenExpiresAt) {
        return seedSource(SeedSpec.builder()
                .status(ConnectedSourceStatus.REVOKED)
                .accessToken("at-any")
                .refreshToken("rt-any")
                .accessTokenExpiresAt(accessTokenExpiresAt)
                .scopes(List.of())
                .build());
    }

    public UUID seedSource(SeedSpec spec) {
        return tx.execute(status -> {
            UserEntity user = userRepository.saveAndFlush(UserEntity.builder()
                    .id(UUID.randomUUID())
                    .displayName(spec.displayName == null ? "test-user" : spec.displayName)
                    .build());
            connectedSourceRepository.saveAndFlush(ConnectedSourceEntity.builder()
                    .id(spec.sourceId == null ? UUID.randomUUID() : spec.sourceId)
                    .userId(user.getId())
                    .provider(DataProvider.STRAVA)
                    .providerUserId(spec.providerUserId == null
                            ? UUID.randomUUID().toString()
                            : spec.providerUserId)
                    .accessToken(spec.accessToken)
                    .refreshToken(spec.refreshToken)
                    .accessTokenExpiresAt(spec.accessTokenExpiresAt)
                    .refreshTokenExpiresAt(spec.refreshTokenExpiresAt)
                    // ArrayList so services that clear-in-place (markRevoked) don't fail.
                    .scopes(new ArrayList<>(spec.scopes == null
                            ? List.of("read")
                            : spec.scopes))
                    .status(spec.status)
                    .failureCount(spec.failureCount)
                    .lastError(spec.lastError)
                    .build());
            return user.getId();
        });
    }

    public record SeedSpec(
            UUID sourceId,
            String displayName,
            String providerUserId,
            String accessToken,
            String refreshToken,
            Instant accessTokenExpiresAt,
            Instant refreshTokenExpiresAt,
            List<String> scopes,
            ConnectedSourceStatus status,
            int failureCount,
            String lastError
    ) {
        public static SeedSpecBuilder builder() { return new SeedSpecBuilder(); }

        public static final class SeedSpecBuilder {
            private UUID sourceId;
            private String displayName;
            private String providerUserId;
            private String accessToken = "at";
            private String refreshToken = "rt";
            private Instant accessTokenExpiresAt;
            private Instant refreshTokenExpiresAt;
            private List<String> scopes;
            private ConnectedSourceStatus status = ConnectedSourceStatus.ACTIVE;
            private int failureCount;
            private String lastError;

            public SeedSpecBuilder sourceId(UUID v)                       { this.sourceId = v; return this; }
            public SeedSpecBuilder displayName(String v)                  { this.displayName = v; return this; }
            public SeedSpecBuilder providerUserId(String v)               { this.providerUserId = v; return this; }
            public SeedSpecBuilder accessToken(String v)                  { this.accessToken = v; return this; }
            public SeedSpecBuilder refreshToken(String v)                 { this.refreshToken = v; return this; }
            public SeedSpecBuilder accessTokenExpiresAt(Instant v)        { this.accessTokenExpiresAt = v; return this; }
            public SeedSpecBuilder refreshTokenExpiresAt(Instant v)       { this.refreshTokenExpiresAt = v; return this; }
            public SeedSpecBuilder scopes(List<String> v)                 { this.scopes = v; return this; }
            public SeedSpecBuilder status(ConnectedSourceStatus v)        { this.status = v; return this; }
            public SeedSpecBuilder failureCount(int v)                    { this.failureCount = v; return this; }
            public SeedSpecBuilder lastError(String v)                    { this.lastError = v; return this; }

            public SeedSpec build() {
                return new SeedSpec(sourceId, displayName, providerUserId,
                        accessToken, refreshToken,
                        accessTokenExpiresAt, refreshTokenExpiresAt,
                        scopes, status, failureCount, lastError);
            }
        }
    }
}
