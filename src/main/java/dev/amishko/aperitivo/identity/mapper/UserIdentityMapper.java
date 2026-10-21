package dev.amishko.aperitivo.identity.mapper;

import dev.amishko.aperitivo.identity.dto.ProviderIdentityProfile;
import dev.amishko.aperitivo.identity.repository.entity.UserIdentityEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class UserIdentityMapper {

    public UserIdentityEntity toEntity(ProviderIdentityProfile profile, UUID userId) {
        return UserIdentityEntity.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .provider(profile.provider())
                .providerUserId(profile.providerUserId())
                .lastLoginAt(Instant.now())
                .build();
    }
}
