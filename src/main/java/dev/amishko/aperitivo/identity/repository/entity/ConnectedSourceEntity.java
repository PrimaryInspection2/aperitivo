package dev.amishko.aperitivo.identity.repository.entity;

import dev.amishko.aperitivo.identity.dto.ConnectedSourceStatus;
import dev.amishko.aperitivo.identity.dto.DataProvider;
import dev.amishko.aperitivo.identity.util.cipher.EncryptedBytesConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(schema = "identity", name = "connected_sources")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Setter
@Getter
@ToString
public class ConnectedSourceEntity implements Persistable<UUID> {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private DataProvider provider;

    @Column(name = "provider_user_id", nullable = false, updatable = false)
    private String providerUserId;

    @Column(name = "access_token", nullable = false, columnDefinition = "bytea")
    @ToString.Exclude
    @Convert(converter = EncryptedBytesConverter.class)
    private String accessToken;

    @Column(name = "refresh_token", nullable = false, columnDefinition = "bytea")
    @ToString.Exclude
    @Convert(converter = EncryptedBytesConverter.class)
    private String refreshToken;

    @Column(name = "access_token_expires_at", nullable = false)
    @ToString.Exclude
    private Instant accessTokenExpiresAt;

    @Column(name = "refresh_token_expires_at")
    @ToString.Exclude
    private Instant refreshTokenExpiresAt;

    @Column(name = "scopes", nullable = false, columnDefinition = "text[]")
    @Builder.Default
    @JdbcTypeCode(SqlTypes.ARRAY)
    @ToString.Exclude
    private List<String> scopes = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ConnectedSourceStatus status;

    @Column(name = "failure_count", nullable = false)
    @Builder.Default
    private int failureCount = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "last_refreshed_at")
    private Instant lastRefreshedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    @Override
    public boolean isNew() {
        return Objects.isNull(this.createdAt) || Objects.isNull(this.updatedAt);
    }
}
