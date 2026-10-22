package dev.amishko.aperitivo.identity.service;

import dev.amishko.aperitivo.identity.dto.LoginResult;
import dev.amishko.aperitivo.identity.dto.ProviderIdentityProfile;
import dev.amishko.aperitivo.identity.events.dto.UserRegisteredEvent;
import dev.amishko.aperitivo.identity.mapper.UserEntityMapper;
import dev.amishko.aperitivo.identity.mapper.UserIdentityMapper;
import dev.amishko.aperitivo.identity.repository.UserIdentityRepository;
import dev.amishko.aperitivo.identity.repository.UserRepository;
import dev.amishko.aperitivo.identity.repository.entity.UserEntity;
import dev.amishko.aperitivo.identity.repository.entity.UserIdentityEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@Slf4j
@RequiredArgsConstructor
public class OAuth2GoogleLoginCallbackService {

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;

    private final UserEntityMapper userEntityMapper;
    private final UserIdentityMapper userIdentityMapper;

    private final ApplicationEventPublisher eventPublisher;


    @Retryable(retryFor = DataIntegrityViolationException.class, maxAttempts = 3, backoff = @Backoff(delay = 50))
    @Transactional
    public LoginResult handleLogin(ProviderIdentityProfile profile) {
        return userIdentityRepository.findByProviderAndProviderUserId(profile.provider(), profile.providerUserId())
                .map(userIdentity -> {
                    userIdentity.setLastLoginAt(Instant.now());
                    return new LoginResult(userIdentity.getUserId(), false);
                })
                .orElseGet(() -> register(profile));
    }

    private LoginResult register(ProviderIdentityProfile profile) {
        UserEntity user = userEntityMapper.toEntity(profile);
        userRepository.save(user);

        UserIdentityEntity userIdentity = userIdentityMapper.toEntity(profile, user.getId());
        userIdentityRepository.save(userIdentity);
        eventPublisher.publishEvent(new UserRegisteredEvent(user.getId()));
        return new LoginResult(user.getId(), true);
    }
}
