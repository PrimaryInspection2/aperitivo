package dev.amishko.aperitivo.identity.service.aperitivo;

import dev.amishko.aperitivo.identity.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Component
@Slf4j
@RequiredArgsConstructor
public class ExpiredRefreshTokenCleanerJob {

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    @Transactional
    @Scheduled(cron = "${aperitivo.identity.scheduler.expired-token-cleaner.cron}")
    public void deleteExpiredTokens() {
        Instant now = Instant.now(clock);
        int deletedCount = refreshTokenRepository.deleteAllExpiredTokens(now);
        log.info("Deleted {} expired tokens", deletedCount);
    }
}
