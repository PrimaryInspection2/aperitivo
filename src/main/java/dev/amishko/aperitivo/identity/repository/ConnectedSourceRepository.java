package dev.amishko.aperitivo.identity.repository;

import dev.amishko.aperitivo.identity.dto.DataProvider;
import dev.amishko.aperitivo.identity.repository.entity.ConnectedSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectedSourceRepository extends JpaRepository<ConnectedSourceEntity, UUID> {

    Optional<ConnectedSourceEntity> findByUserIdAndProvider(UUID userId, DataProvider provider);
}
