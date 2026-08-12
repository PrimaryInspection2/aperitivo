package dev.amishko.aperitivo.identity.mapper;

import dev.amishko.aperitivo.identity.dto.ProviderIdentityProfile;
import dev.amishko.aperitivo.identity.repository.entity.UserEntity;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UserEntityMapper {

    public UserEntity toEntity(ProviderIdentityProfile userProfile) {
        return UserEntity.builder()
                .id(UUID.randomUUID())
                .email(userProfile.email())
                .displayName(userProfile.displayName())
                .avatarUrl(userProfile.avatarUrl())
                .build();
    }
}
