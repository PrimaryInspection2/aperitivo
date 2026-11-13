package dev.amishko.aperitivo.identity.service.auth;

import dev.amishko.aperitivo.identity.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static dev.amishko.aperitivo.identity.util.cipher.TokenHashUtil.hashToken;
import static org.apache.commons.lang3.StringUtils.isBlank;

@Service
@Slf4j
@RequiredArgsConstructor
public class LogoutService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    @Transactional
    public void logout(String currentToken) {
        Instant now = Instant.now(clock);

        if (isBlank(currentToken)) {
            return;
        }
        String currentTokenHash = hashToken(currentToken);

        refreshTokenRepository.findByTokenHashForUpdate(currentTokenHash)
                .filter(refreshToken -> refreshToken.getRevokedAt() == null)
                .ifPresent(token -> refreshTokenRepository.revokeTokenFamily(token.getUserId(), token.getFamilyId(), now));
    }

    @Transactional
    public void logoutFromAllDevices(UUID userId) {
        Instant now = Instant.now(clock);
        int revokedCount = refreshTokenRepository.revokeAllUserTokenFamilies(userId, now);
        log.info("Revoked {} tokens for user {}", revokedCount, userId);
    }
}
