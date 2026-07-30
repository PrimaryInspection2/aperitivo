package dev.amishko.aperitivo.identity.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(schema = "identity", name = "users")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Setter
@Getter
public class UserEntity implements Persistable<UUID> {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "email")
    private String email;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    @Setter(AccessLevel.NONE)
    @ToString.Exclude
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    @Setter(AccessLevel.NONE)
    @ToString.Exclude
    private Instant updatedAt;

    @Override
    public boolean isNew() {
        return Objects.isNull(this.createdAt) || Objects.isNull(this.updatedAt);
    }
}
