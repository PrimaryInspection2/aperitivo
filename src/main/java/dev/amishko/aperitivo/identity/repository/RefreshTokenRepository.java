package dev.amishko.aperitivo.identity.repository;

import dev.amishko.aperitivo.identity.repository.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, String> {

    //we need these validation for usedAt/revokedAt to handle more gracefully case when 2 parallel token's refresh happened.
    //We have unique index to catch this, but it will throw DataIntegrityException and will abort transaction.
    //But I want to gracefully handle that case in initial tx.
    @Modifying
    @Query("UPDATE RefreshTokenEntity SET usedAt = now() where tokenHash =:tokenHash AND usedAt IS NULL AND revokedAt IS NULL")
    int markTokenAsUsed(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshTokenEntity SET revokedAt = now() where userId =:userId AND familyId =:familyId AND revokedAt IS NULL")
    int revokeTokenFamily(UUID userId, UUID familyId);

    @Query("SELECT MIN(familyCreatedAt) FROM RefreshTokenEntity WHERE userId=:userId AND familyId =:familyId")
    Instant findOldestTokenInFamily(UUID userId, UUID familyId);

    @Modifying
    @Query("DELETE FROM RefreshTokenEntity WHERE familyCreatedAt <:cutoffDate")
    int deleteAllExpiredTokens(Instant cutoffDate);
}
