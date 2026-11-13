package dev.amishko.aperitivo.identity.service.aperitivo;

import dev.amishko.aperitivo.identity.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
@Slf4j
@RequiredArgsConstructor
public class ExpiredRefreshTokenCleanerJob {

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    @Scheduled(cron = "${aperitivo.identity.scheduler.expired-token-cleaner.cron}")
    public void deleteExpiredTokens() {
        Instant now = Instant.now(clock);
        int deletedCount = 0;

        try {
            deletedCount = refreshTokenRepository.deleteAllExpiredTokens(now);
        } catch (Exception ex) {
            log.error("Error while cleaning expired tokens", ex);
        }
        log.debug("Deleted {} expired tokens", deletedCount);
    }
}
