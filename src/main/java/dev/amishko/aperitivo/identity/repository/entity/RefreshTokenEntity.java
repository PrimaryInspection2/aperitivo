package dev.amishko.aperitivo.identity.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(schema = "identity", name = "refresh_tokens")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Setter
@Getter
public class RefreshTokenEntity implements Persistable<String> {

    @Id
    @Column(name = "token_hash", nullable = false)
    @ToString.Exclude
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @ToString.Exclude
    private Instant createdAt;

    @Column(name = "family_created_at", nullable = false, updatable = false)
    @ToString.Exclude
    private Instant familyCreatedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    @ToString.Exclude
    private Instant expiresAt;

    @Column(name = "used_at")
    @ToString.Exclude
    private Instant usedAt;

    @Column(name = "revoked_at")
    @ToString.Exclude
    private Instant revokedAt;


    @Override
    public @Nullable String getId() {
        return this.tokenHash;
    }

    @Override
    public boolean isNew() {
        return Objects.isNull(this.createdAt);
    }

}