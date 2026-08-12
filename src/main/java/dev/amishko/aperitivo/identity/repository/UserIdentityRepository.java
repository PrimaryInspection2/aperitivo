package dev.amishko.aperitivo.identity.repository;

import dev.amishko.aperitivo.identity.dto.IdentityProvider;
import dev.amishko.aperitivo.identity.repository.entity.UserIdentityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserIdentityRepository extends JpaRepository<UserIdentityEntity, UUID> {

    Optional<UserIdentityEntity> findByProviderAndProviderUserId(IdentityProvider provider, String providerUserId);
}
