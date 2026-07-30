package dev.amishko.aperitivo.config;

import dev.amishko.aperitivo.AperitivoApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.PublishedEventsExtension;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = {
        AperitivoApplication.class,
        ContainersConfiguration.class
})
@Import({TestDataCleaner.class, RecordedEvents.class, TestFixtures.class})
@ActiveProfiles("itest")
@EnableScenarios
@ExtendWith(PublishedEventsExtension.class)
public abstract class BaseIT {

    @Autowired
    private TestDataCleaner testDataCleaner;

    @Autowired
    protected RecordedEvents recordedEvents;

    @Autowired
    protected TestFixtures fixtures;

    @BeforeEach
    void resetTestState() {
        testDataCleaner.cleanAll();
        recordedEvents.clear();
    }
}
