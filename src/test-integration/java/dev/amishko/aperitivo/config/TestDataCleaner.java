package dev.amishko.aperitivo.config;

import dev.amishko.aperitivo.identity.repository.ConnectedSourceRepository;
import dev.amishko.aperitivo.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@TestComponent
@RequiredArgsConstructor
public class TestDataCleaner {

    private final ConnectedSourceRepository connectedSourceRepository;
    private final UserRepository userRepository;
    private final RedissonClient redisson;
    private final JdbcTemplate jdbc;

    @Transactional
    public void cleanAll() {
        connectedSourceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        jdbc.execute("TRUNCATE TABLE event_publication");
        redisson.getKeys().flushdb();
    }
}

